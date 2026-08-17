package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.constant.PositionRoles;
import com.bu.management.dto.WorkflowConfigRequest;
import com.bu.management.entity.WorkflowConfig;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.WorkflowConfigMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowConfigService {

    private static final List<String> PROJECT_STATUSES = List.of(
            "待评估", "评估中", "已拒绝", "转产品需求", "待设计", "设计中",
            "待确认", "开发中", "测试中", "待上线", "已上线", "已交付", "已验收"
    );
    private static final List<String> PRODUCT_STATUSES = List.of(
            "待评估", "评估中", "已拒绝", "待设计", "设计中",
            "待确认", "开发中", "测试中", "待上线", "已上线"
    );
    private static final Map<String, String> REQUIREMENT_TYPE_ALIASES = Map.of(
            "PROJECT", "项目需求",
            "PRODUCT", "产品需求"
    );
    private static final Set<String> ALLOWED_ROLES = PositionRoles.WORKFLOW_ROLE_LABELS;

    private final WorkflowConfigMapper workflowConfigMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<WorkflowConfig> getAllConfigs() {
        LambdaQueryWrapper<WorkflowConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(WorkflowConfig::getRequirementType)
                .orderByAsc(WorkflowConfig::getSortOrder)
                .orderByAsc(WorkflowConfig::getId);
        return workflowConfigMapper.selectList(wrapper);
    }

    public WorkflowConfig getById(Long id) {
        WorkflowConfig config = workflowConfigMapper.selectById(id);
        if (config == null) {
            throw new ResourceNotFoundException("工作流配置不存在");
        }
        return config;
    }

    public List<WorkflowConfig> getConfigsByType(String requirementType) {
        LambdaQueryWrapper<WorkflowConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowConfig::getRequirementType, normalizeRequirementType(requirementType))
                .eq(WorkflowConfig::getIsActive, 1)
                .orderByAsc(WorkflowConfig::getSortOrder)
                .orderByAsc(WorkflowConfig::getId);
        return workflowConfigMapper.selectList(wrapper);
    }

    public List<String> getNextStatuses(String requirementType, String currentStatus) {
        LambdaQueryWrapper<WorkflowConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkflowConfig::getRequirementType, normalizeRequirementType(requirementType))
                .eq(WorkflowConfig::getFromStatus, currentStatus)
                .eq(WorkflowConfig::getIsActive, 1)
                .orderByAsc(WorkflowConfig::getSortOrder)
                .orderByAsc(WorkflowConfig::getId);
        return workflowConfigMapper.selectList(wrapper).stream()
                .map(WorkflowConfig::getToStatus)
                .toList();
    }

    public Map<String, List<String>> getStatusOptions() {
        return Map.of(
                "项目需求", PROJECT_STATUSES,
                "产品需求", PRODUCT_STATUSES
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowConfig create(WorkflowConfigRequest request) {
        WorkflowConfig config = new WorkflowConfig();
        applyRequest(config, request);
        workflowConfigMapper.insert(config);
        return config;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkflowConfig update(Long id, WorkflowConfigRequest request) {
        WorkflowConfig config = getById(id);
        applyRequest(config, request);
        workflowConfigMapper.updateById(config);
        return config;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        workflowConfigMapper.deleteById(id);
    }

    private void applyRequest(WorkflowConfig config, WorkflowConfigRequest request) {
        String requirementType = normalizeRequirementType(request.getRequirementType());
        String fromStatus = normalizeText(request.getFromStatus());
        String toStatus = normalizeText(request.getToStatus());
        List<String> allowedRoles = normalizeRoles(request.getAllowedRoles());

        validateStatuses(requirementType, fromStatus, toStatus);

        config.setRequirementType(requirementType);
        config.setFromStatus(fromStatus);
        config.setToStatus(toStatus);
        config.setAllowedRoles(serializeRoles(allowedRoles));
        config.setConditionType(normalizeConditionType(request.getConditionType()));
        config.setIsActive(request.getIsActive());
        config.setSortOrder(request.getSortOrder());
    }

    private String normalizeRequirementType(String requirementType) {
        String normalized = normalizeText(requirementType);
        return REQUIREMENT_TYPE_ALIASES.getOrDefault(normalized, normalized);
    }

    private List<String> normalizeRoles(List<String> roles) {
        List<String> normalizedRoles = roles.stream()
                .map(this::normalizeText)
                .map(PositionRoles::normalizeWorkflowRole)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        if (normalizedRoles.isEmpty()) {
            throw new RuntimeException("允许角色不能为空");
        }

        List<String> invalidRoles = normalizedRoles.stream()
                .filter(role -> !ALLOWED_ROLES.contains(role))
                .toList();
        if (!invalidRoles.isEmpty()) {
            throw new RuntimeException("存在无效角色: " + String.join(", ", invalidRoles));
        }

        return normalizedRoles;
    }

    private void validateStatuses(String requirementType, String fromStatus, String toStatus) {
        if (fromStatus.equals(toStatus)) {
            throw new RuntimeException("当前状态和目标状态不能相同");
        }

        List<String> allowedStatuses;
        if ("项目需求".equals(requirementType)) {
            allowedStatuses = PROJECT_STATUSES;
        } else if ("产品需求".equals(requirementType)) {
            allowedStatuses = PRODUCT_STATUSES;
        } else {
            throw new RuntimeException("需求类型不合法");
        }

        if (!allowedStatuses.contains(fromStatus)) {
            throw new RuntimeException("当前状态不属于该需求类型");
        }
        if (!allowedStatuses.contains(toStatus)) {
            throw new RuntimeException("目标状态不属于该需求类型");
        }
    }

    private String normalizeConditionType(String conditionType) {
        String normalized = normalizeText(conditionType);
        if (!StringUtils.hasText(normalized) || "NONE".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String serializeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("允许角色序列化失败");
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
