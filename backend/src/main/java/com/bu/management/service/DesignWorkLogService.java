package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateDesignWorkLogDTO;
import com.bu.management.dto.UpdateDesignWorkLogDTO;
import com.bu.management.entity.DesignWorkLog;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementDesign;
import com.bu.management.mapper.DesignWorkLogMapper;
import com.bu.management.mapper.RequirementDesignMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设计工作记录服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class DesignWorkLogService {

    private static final String PROTOTYPE = "原型设计";
    private static final String UI = "UI设计";
    private static final String TECH = "技术方案设计";

    private final DesignWorkLogMapper workLogMapper;
    private final RequirementMapper requirementMapper;
    private final RequirementDesignMapper requirementDesignMapper;

    /**
     * 创建设计工作记录
     */
    /** Creates a design log with the authenticated actor as designer. */
    @Transactional
    public DesignWorkLog createWorkLog(CreateDesignWorkLogDTO dto, Long actorId) {
        if (actorId == null) {
            throw new RuntimeException("当前登录用户不存在");
        }
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        if (!StringUtils.hasText(dto.getWorkType())) {
            throw new RuntimeException("设计环节不能为空");
        }
        if (actorId == null) {
            throw new RuntimeException("设计负责人不能为空");
        }

        LambdaQueryWrapper<DesignWorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DesignWorkLog::getRequirementId, dto.getRequirementId())
                .eq(DesignWorkLog::getWorkType, dto.getWorkType());
        DesignWorkLog workLog = workLogMapper.selectOne(wrapper);

        if (workLog == null) {
            workLog = new DesignWorkLog();
            workLog.setRequirementId(dto.getRequirementId());
            workLog.setWorkType(dto.getWorkType());
            workLog.setCreatedAt(LocalDateTime.now());
        }

        applyWorkLog(workLog, actorId, dto.getEstimatedHours(), dto.getWorkContent(), dto.getPlannedCompletedAt(), dto.getStatus());
        if (workLog.getId() == null) {
            workLogMapper.insert(workLog);
        } else {
            workLogMapper.updateById(workLog);
        }
        syncRequirementDesign(requirement.getId());
        return workLog;
    }

    /**
     * 更新设计工作记录
     */
    /** Updates a design log without permitting a body to forge its designer. */
    @Transactional
    public DesignWorkLog updateWorkLog(Long id, UpdateDesignWorkLogDTO dto, Long actorId) {
        if (actorId == null) {
            throw new RuntimeException("当前登录用户不存在");
        }
        DesignWorkLog workLog = workLogMapper.selectById(id);
        if (workLog == null) {
            throw new RuntimeException("工作记录不存在");
        }

        if (dto.getActualHours() != null) {
            workLog.setActualHours(dto.getActualHours());
        }
        // designerId is an audit actor and must remain the authenticated user.
        workLog.setDesignerId(actorId);
        if (dto.getEstimatedHours() != null) {
            workLog.setEstimatedHours(dto.getEstimatedHours());
        }
        if (dto.getResultUrl() != null) {
            workLog.setResultUrl(dto.getResultUrl());
        }
        if (dto.getWorkContent() != null) {
            workLog.setWorkContent(dto.getWorkContent());
        }
        if (dto.getPlannedCompletedAt() != null) {
            workLog.setPlannedCompletedAt(dto.getPlannedCompletedAt());
        }
        if (dto.getStatus() != null) {
            workLog.setStatus(dto.getStatus());
            if ("进行中".equals(dto.getStatus()) && workLog.getStartedAt() == null) {
                workLog.setStartedAt(LocalDateTime.now());
            }
            if ("已完成".equals(dto.getStatus())) {
                workLog.setCompletedAt(LocalDateTime.now());
            } else {
                workLog.setCompletedAt(null);
            }
        }
        workLog.setUpdatedAt(LocalDateTime.now());

        workLogMapper.updateById(workLog);
        syncRequirementDesign(workLog.getRequirementId());
        return workLog;
    }

    /**
     * 查询需求的设计工作记录列表
     */
    public List<DesignWorkLog> getWorkLogsByRequirementId(Long requirementId) {
        LambdaQueryWrapper<DesignWorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DesignWorkLog::getRequirementId, requirementId);
        wrapper.orderByDesc(DesignWorkLog::getCreatedAt);
        return workLogMapper.selectList(wrapper);
    }

    /**
     * 分页查询设计工作记录
     */
    public Page<DesignWorkLog> getWorkLogsPage(int page, int size, Long requirementId, String workType, String status) {
        Page<DesignWorkLog> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<DesignWorkLog> wrapper = new LambdaQueryWrapper<>();

        if (requirementId != null) {
            wrapper.eq(DesignWorkLog::getRequirementId, requirementId);
        }
        if (workType != null && !workType.isEmpty()) {
            wrapper.eq(DesignWorkLog::getWorkType, workType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DesignWorkLog::getStatus, status);
        }

        wrapper.orderByDesc(DesignWorkLog::getCreatedAt);
        return workLogMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 删除设计工作记录
     */
    @Transactional
    public void deleteWorkLog(Long id) {
        DesignWorkLog workLog = workLogMapper.selectById(id);
        if (workLog == null) {
            return;
        }
        workLogMapper.deleteById(id);
        syncRequirementDesign(workLog.getRequirementId());
    }

    private void applyWorkLog(
            DesignWorkLog workLog,
            Long designerId,
            java.math.BigDecimal estimatedHours,
            String workContent,
            LocalDateTime plannedCompletedAt,
            String status
    ) {
        workLog.setDesignerId(designerId);
        workLog.setEstimatedHours(estimatedHours);
        workLog.setWorkContent(workContent);
        workLog.setPlannedCompletedAt(plannedCompletedAt);
        workLog.setStatus(StringUtils.hasText(status) ? status : "待开始");
        if ("进行中".equals(workLog.getStatus()) && workLog.getStartedAt() == null) {
            workLog.setStartedAt(LocalDateTime.now());
        }
        if (!"已完成".equals(workLog.getStatus())) {
            workLog.setCompletedAt(null);
        }
        workLog.setUpdatedAt(LocalDateTime.now());
    }

    private void syncRequirementDesign(Long requirementId) {
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            return;
        }

        List<DesignWorkLog> logs = getWorkLogsByRequirementId(requirementId);

        if (logs.isEmpty()) {
            return;
        }

        LambdaQueryWrapper<RequirementDesign> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDesign::getRequirementId, requirementId);
        RequirementDesign design = requirementDesignMapper.selectOne(wrapper);
        if (design == null) {
            design = new RequirementDesign();
            design.setRequirementId(requirementId);
            design.setCreatedAt(LocalDateTime.now());
        }

        design.setPrototypeStatus(resolveStatus(logs, PROTOTYPE));
        design.setUiStatus(resolveStatus(logs, UI));
        design.setTechSolutionStatus(resolveStatus(logs, TECH));
        design.setUpdatedAt(LocalDateTime.now());

        boolean allCompleted = logs.stream().allMatch(log -> "已完成".equals(log.getStatus()));
        if (allCompleted) {
            design.setAllCompletedAt(LocalDateTime.now());
            if ("设计中".equals(requirement.getStatus())) {
                requirement.setStatus("待确认");
                requirement.setUpdatedAt(LocalDateTime.now());
                requirementMapper.updateById(requirement);
            }
        } else {
            design.setAllCompletedAt(null);
        }

        if (design.getId() == null) {
            requirementDesignMapper.insert(design);
        } else {
            requirementDesignMapper.updateById(design);
        }
    }

    private String resolveStatus(List<DesignWorkLog> logs, String workType) {
        return logs.stream()
                .filter(log -> workType.equals(log.getWorkType()))
                .findFirst()
                .map(log -> switch (log.getStatus()) {
                    case "进行中" -> "进行中";
                    case "已完成" -> "已完成";
                    default -> "未开始";
                })
                .orElse("已完成");
    }
}
