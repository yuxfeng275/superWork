package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.YunxiaoHandoffEvent;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkitemLink;
import com.bu.management.integration.YunxiaoClient;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.YunxiaoHandoffEventMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemLinkMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class YunxiaoHandoffService {

    private final YunxiaoConfigService configService;
    private final YunxiaoClient client;
    private final RequirementMapper requirementMapper;
    private final ProjectMapper projectMapper;
    private final YunxiaoProjectMappingMapper projectMappingMapper;
    private final YunxiaoUserMappingMapper userMappingMapper;
    private final YunxiaoWorkitemLinkMapper linkMapper;
    private final YunxiaoHandoffEventMapper eventMapper;

    @Transactional
    public void enqueue(Long requirementId) {
        YunxiaoWorkitemLink link = findLink(requirementId);
        if (link == null) {
            Requirement requirement = requirementMapper.selectById(requirementId);
            link = new YunxiaoWorkitemLink();
            link.setRequirementId(requirementId);
            link.setProjectId(requirement == null ? null : requirement.getProjectId());
            link.setSyncStatus("PENDING");
            link.setCreatedAt(LocalDateTime.now());
            link.setUpdatedAt(LocalDateTime.now());
            linkMapper.insert(link);
        }

        YunxiaoHandoffEvent event = eventMapper.selectOne(new LambdaQueryWrapper<YunxiaoHandoffEvent>()
                .eq(YunxiaoHandoffEvent::getRequirementId, requirementId));
        if (event == null) {
            event = new YunxiaoHandoffEvent();
            event.setRequirementId(requirementId);
            event.setStatus("PENDING");
            event.setAttemptCount(0);
            event.setCreatedAt(LocalDateTime.now());
            event.setUpdatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } else if (!"SUCCESS".equals(event.getStatus())) {
            event.setStatus("PENDING");
            event.setNextRetryAt(LocalDateTime.now());
            event.setUpdatedAt(LocalDateTime.now());
            eventMapper.updateById(event);
        }
    }

    @Scheduled(cron = "${yunxiao.handoff-retry-cron:0 */10 * * * *}", zone = "Asia/Shanghai")
    public void processPendingEvents() {
        if (!configService.getRuntimeConfig().isConfigured()) {
            return;
        }
        List<YunxiaoHandoffEvent> events = eventMapper.selectList(
                new LambdaQueryWrapper<YunxiaoHandoffEvent>()
                        .in(YunxiaoHandoffEvent::getStatus, "PENDING", "FAILED")
                        .and(wrapper -> wrapper.isNull(YunxiaoHandoffEvent::getNextRetryAt)
                                .or().le(YunxiaoHandoffEvent::getNextRetryAt, LocalDateTime.now()))
                        .orderByAsc(YunxiaoHandoffEvent::getCreatedAt)
                        .last("LIMIT 20"));
        events.forEach(this::process);
    }

    public YunxiaoWorkitemLink retry(Long requirementId) {
        enqueue(requirementId);
        YunxiaoHandoffEvent event = eventMapper.selectOne(new LambdaQueryWrapper<YunxiaoHandoffEvent>()
                .eq(YunxiaoHandoffEvent::getRequirementId, requirementId));
        if (configService.getRuntimeConfig().isConfigured()) {
            process(event);
        }
        return findLink(requirementId);
    }

    public YunxiaoWorkitemLink bind(Long requirementId, String workitemId, String serialNumber) {
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }
        if (!StringUtils.hasText(workitemId)) {
            throw new RuntimeException("云效工作项ID不能为空");
        }
        YunxiaoWorkitemLink link = findLink(requirementId);
        if (link == null) {
            link = new YunxiaoWorkitemLink();
            link.setRequirementId(requirementId);
            link.setProjectId(requirement.getProjectId());
            link.setCreatedAt(LocalDateTime.now());
        }
        link.setYunxiaoWorkitemId(workitemId.trim());
        link.setSerialNumber(serialNumber);
        link.setSyncStatus("BOUND");
        link.setLastError(null);
        link.setLastSyncedAt(LocalDateTime.now());
        link.setUpdatedAt(LocalDateTime.now());
        if (link.getId() == null) {
            linkMapper.insert(link);
        } else {
            linkMapper.updateById(link);
        }
        markEventSuccess(requirementId);
        return link;
    }

    public YunxiaoWorkitemLink getLink(Long requirementId) {
        return findLink(requirementId);
    }

    private void process(YunxiaoHandoffEvent event) {
        if (event == null || "SUCCESS".equals(event.getStatus())) {
            return;
        }
        try {
            Requirement requirement = requirementMapper.selectById(event.getRequirementId());
            if (requirement == null || !"开发中".equals(requirement.getStatus())) {
                throw new IllegalStateException("需求不存在或尚未进入开发中");
            }
            if (requirement.getProjectId() == null) {
                throw new IllegalStateException("需求未关联本地项目");
            }
            YunxiaoProjectMapping projectMapping = projectMappingMapper.selectOne(
                    new LambdaQueryWrapper<YunxiaoProjectMapping>()
                            .eq(YunxiaoProjectMapping::getProjectId, requirement.getProjectId())
                            .eq(YunxiaoProjectMapping::getSyncEnabled, 1));
            if (projectMapping == null) {
                throw new IllegalStateException("需求所属项目尚未配置云效映射");
            }
            if (!StringUtils.hasText(projectMapping.getWorkitemTypeId())) {
                throw new IllegalStateException("项目尚未配置云效需求工作项类型ID");
            }

            Project project = projectMapper.selectById(requirement.getProjectId());
            Long preferredOwnerId = project != null && project.getManagerId() != null
                    ? project.getManagerId() : requirement.getCreatorId();
            YunxiaoUserMapping ownerMapping = userMappingMapper.selectOne(
                    new LambdaQueryWrapper<YunxiaoUserMapping>()
                            .eq(YunxiaoUserMapping::getUserId, preferredOwnerId)
                            .eq(YunxiaoUserMapping::getSyncEnabled, 1));
            if (ownerMapping == null && !Objects.equals(preferredOwnerId, requirement.getCreatorId())) {
                ownerMapping = userMappingMapper.selectOne(new LambdaQueryWrapper<YunxiaoUserMapping>()
                        .eq(YunxiaoUserMapping::getUserId, requirement.getCreatorId())
                        .eq(YunxiaoUserMapping::getSyncEnabled, 1));
            }
            if (ownerMapping == null) {
                throw new IllegalStateException("项目负责人或需求创建人尚未配置云效用户映射");
            }

            String subject = "[" + requirement.getReqNo() + "] " + requirement.getTitle();
            JsonNode existing = client.searchWorkitems(
                            projectMapping.getYunxiaoProjectId(),
                            projectMapping.getCategory())
                    .stream()
                    .filter(item -> subject.equals(item.path("subject").asText()))
                    .findFirst()
                    .orElse(null);
            JsonNode workitem;
            String workitemId;
            if (existing != null) {
                workitem = existing;
                workitemId = existing.path("id").asText();
            } else {
                JsonNode created = client.createWorkitem(
                        projectMapping.getYunxiaoProjectId(),
                        projectMapping.getWorkitemTypeId(),
                        ownerMapping.getYunxiaoUserId(),
                        subject,
                        requirement.getDescription());
                workitemId = created.path("id").asText();
                if (!StringUtils.hasText(workitemId)) {
                    throw new IllegalStateException("云效创建工作项成功但未返回ID");
                }
                workitem = client.getWorkitem(workitemId);
            }
            bind(requirement.getId(), workitemId, workitem.path("serialNumber").asText(null));
        } catch (Exception ex) {
            markFailed(event, ex);
        }
    }

    private YunxiaoWorkitemLink findLink(Long requirementId) {
        return linkMapper.selectOne(new LambdaQueryWrapper<YunxiaoWorkitemLink>()
                .eq(YunxiaoWorkitemLink::getRequirementId, requirementId));
    }

    private void markEventSuccess(Long requirementId) {
        YunxiaoHandoffEvent event = eventMapper.selectOne(new LambdaQueryWrapper<YunxiaoHandoffEvent>()
                .eq(YunxiaoHandoffEvent::getRequirementId, requirementId));
        if (event != null) {
            event.setStatus("SUCCESS");
            event.setLastError(null);
            event.setNextRetryAt(null);
            event.setUpdatedAt(LocalDateTime.now());
            eventMapper.updateById(event);
        }
    }

    private void markFailed(YunxiaoHandoffEvent event, Exception ex) {
        int attempts = event.getAttemptCount() == null ? 1 : event.getAttemptCount() + 1;
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        event.setStatus("FAILED");
        event.setAttemptCount(attempts);
        event.setLastError(message.substring(0, Math.min(1000, message.length())));
        event.setNextRetryAt(LocalDateTime.now().plusMinutes(Math.min(360, attempts * 10L)));
        event.setUpdatedAt(LocalDateTime.now());
        eventMapper.updateById(event);

        YunxiaoWorkitemLink link = findLink(event.getRequirementId());
        if (link != null) {
            link.setSyncStatus("FAILED");
            link.setLastError(event.getLastError());
            link.setUpdatedAt(LocalDateTime.now());
            linkMapper.updateById(link);
        }
    }
}
