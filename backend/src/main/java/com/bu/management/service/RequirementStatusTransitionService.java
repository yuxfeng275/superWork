package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementEvaluation;
import com.bu.management.mapper.RequirementEvaluationMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.vo.RequirementStatusTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 需求状态流转服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class RequirementStatusTransitionService {

    private final RequirementMapper requirementMapper;
    private final RequirementEvaluationMapper evaluationMapper;

    /**
     * 获取需求状态流转信息
     */
    public RequirementStatusTransition getTransitionInfo(Long requirementId) {
        // 查询需求
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 查询评估记录
        LambdaQueryWrapper<RequirementEvaluation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementEvaluation::getRequirementId, requirementId);
        RequirementEvaluation evaluation = evaluationMapper.selectOne(wrapper);

        // 构建状态流转信息
        RequirementStatusTransition transition = new RequirementStatusTransition();
        transition.setRequirementId(requirement.getId());
        transition.setReqNo(requirement.getReqNo());
        transition.setTitle(requirement.getTitle());
        transition.setCurrentStatus(requirement.getStatus());
        transition.setType(requirement.getType());
        transition.setCreatedAt(requirement.getCreatedAt());
        transition.setUpdatedAt(requirement.getUpdatedAt());

        if (evaluation != null) {
            transition.setHasEvaluation(true);
            transition.setEvaluatedAt(evaluation.getEvaluatedAt());
            transition.setHasDecision(evaluation.getDecision() != null);
            transition.setDecision(evaluation.getDecision());
            transition.setDecisionAt(evaluation.getDecisionAt());
        } else {
            transition.setHasEvaluation(false);
            transition.setHasDecision(false);
        }

        return transition;
    }

    /**
     * 验证状态流转是否合法
     */
    public boolean canTransitionTo(Long requirementId, String targetStatus) {
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            return false;
        }

        String currentStatus = requirement.getStatus();

        // 定义状态流转规则
        switch (currentStatus) {
            case "待评估":
                return "评估中".equals(targetStatus);
            case "评估中":
                return "待设计".equals(targetStatus) || "已拒绝".equals(targetStatus);
            case "待设计":
                return "设计中".equals(targetStatus);
            case "设计中":
                return "待确认".equals(targetStatus);
            case "待确认":
                return "开发中".equals(targetStatus);
            case "开发中":
                return "测试中".equals(targetStatus);
            case "测试中":
                return "待上线".equals(targetStatus);
            case "待上线":
                return "已上线".equals(targetStatus);
            case "已上线":
                return "已交付".equals(targetStatus);
            case "已交付":
                return "已验收".equals(targetStatus);
            default:
                return false;
        }
    }
}
