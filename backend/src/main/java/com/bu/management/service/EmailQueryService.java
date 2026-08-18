package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.entity.EmailMessage;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.integration.AlibabaMailClient.AttachmentMeta;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.entity.Project;
import com.bu.management.vo.EmailMessageDetail;
import com.bu.management.vo.EmailMessageListItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class EmailQueryService {
    private static final int MAX_PAGE_SIZE = 100;
    private final EmailMessageMapper mapper;
    private final ObjectMapper objectMapper;
    private final EmailInterpretationService interpretationService;
    private final ProjectMapper projectMapper;

    public Page<EmailMessageListItem> list(
            Long ownerUserId,
            int page,
            int size,
            LocalDate date,
            String keyword,
            Long projectId,
            boolean ungrouped,
            String senderDomain) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        LambdaQueryWrapper<EmailMessage> query = new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId);
        if (date != null) {
            query.ge(EmailMessage::getReceivedAt, date.atStartOfDay())
                    .lt(EmailMessage::getReceivedAt, date.plusDays(1).atStartOfDay());
        }
        if (ungrouped) {
            query.isNull(EmailMessage::getProjectId);
        } else if (projectId != null) {
            query.eq(EmailMessage::getProjectId, projectId);
        }
        if (StringUtils.hasText(senderDomain)) {
            String normalizedDomain = senderDomain.trim().toLowerCase(java.util.Locale.ROOT);
            if (!normalizedDomain.matches("[a-z0-9.-]+") && !"unknown".equals(normalizedDomain)) {
                throw new IllegalStateException("发件人公司域名格式不正确");
            }
            if ("unknown".equals(normalizedDomain)) {
                query.and(wrapper -> wrapper.isNull(EmailMessage::getSenderAddress)
                        .or().notLike(EmailMessage::getSenderAddress, "@"));
            } else {
                query.like(EmailMessage::getSenderAddress, "@" + normalizedDomain);
            }
        }
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            query.and(wrapper -> wrapper.like(EmailMessage::getSubject, trimmed)
                    .or().like(EmailMessage::getSenderName, trimmed)
                    .or().like(EmailMessage::getSenderAddress, trimmed)
                    .or().like(EmailMessage::getBodyPreview, trimmed));
        }
        query.orderByDesc(EmailMessage::getReceivedAt).orderByDesc(EmailMessage::getId);
        Page<EmailMessage> source = mapper.selectPage(new Page<>(safePage, safeSize), query);
        Page<EmailMessageListItem> result = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        java.util.Set<Long> projectIds = source.getRecords().stream()
                .map(EmailMessage::getProjectId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, Project> projects = projectIds.isEmpty() ? java.util.Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(java.util.stream.Collectors.toMap(Project::getId, project -> project));
        result.setRecords(source.getRecords().stream()
                .map(message -> toListItem(message, projects.get(message.getProjectId()))).toList());
        return result;
    }

    public EmailMessageDetail detail(Long ownerUserId, Long messageId) {
        EmailMessage message = mapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) {
            throw new ResourceNotFoundException("邮件不存在");
        }
        Project project = message.getProjectId() == null ? null
                : projectMapper.selectById(message.getProjectId());
        return new EmailMessageDetail(
                message.getId(),
                message.getInternetMessageId(),
                message.getSubject(),
                message.getSenderName(),
                message.getSenderAddress(),
                message.getReceivedAt(),
                message.getBodyPreview(),
                message.getProjectId(),
                project == null ? null : project.getName(),
                project == null ? null : project.getFullPath(),
                message.getGroupingStatus(),
                message.getGroupingConfidence(),
                message.getGroupingReason(),
                read(message.getToAddressesJson(), new TypeReference<List<String>>() {}, List.of()),
                read(message.getCcAddressesJson(), new TypeReference<List<String>>() {}, List.of()),
                message.getBodyText(),
                read(message.getAttachmentsJson(),
                        new TypeReference<List<AttachmentMeta>>() {}, List.of()),
                interpretationService.toView(message));
    }

    private EmailMessageListItem toListItem(EmailMessage message, Project project) {
        List<AttachmentMeta> attachments = read(message.getAttachmentsJson(),
                new TypeReference<List<AttachmentMeta>>() {}, List.of());
        return new EmailMessageListItem(
                message.getId(), message.getInternetMessageId(), message.getSubject(),
                message.getSenderName(), message.getSenderAddress(), message.getReceivedAt(),
                message.getBodyPreview(), message.getProjectId(), project == null ? null : project.getName(),
                project == null ? null : project.getFullPath(), message.getGroupingStatus(),
                message.getGroupingConfidence(), !attachments.isEmpty(), attachments.size());
    }

    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            return fallback;
        }
    }
}
