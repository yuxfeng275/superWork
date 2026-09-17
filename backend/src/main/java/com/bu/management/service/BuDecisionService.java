package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.BuDecisionRequest;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementEvaluation;
import com.bu.management.mapper.RequirementEvaluationMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * BU决策服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class BuDecisionService {

    private final RequirementMapper requirementMapper;
    private final RequirementEvaluationMapper evaluationMapper;

    /**
     * 提交BU决策
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementEvaluation makeDecision(BuDecisionRequest request, Long decisionBy) {
        // 验证需求是否存在
        Requirement requirement = requirementMapper.selectById(request.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 验证需求状态（只有评估中的需求才能进行决策）
        if (!"评估中".equals(requirement.getStatus())) {
            throw new RuntimeException("只有评估中的需求才能进行决策");
        }

        // 查询评估记录
        LambdaQueryWrapper<RequirementEvaluation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementEvaluation::getRequirementId, request.getRequirementId());
        RequirementEvaluation evaluation = evaluationMapper.selectOne(wrapper);

        if (evaluation == null) {
            throw new RuntimeException("该需求尚未评估，无法进行决策");
        }

        // 验证决策类型
        if (!"通过".equals(request.getDecision()) &&
            !"拒绝".equals(request.getDecision()) &&
            !"转产品需求".equals(request.getDecision())) {
            throw new RuntimeException("决策类型无效，只能是：通过/拒绝/转产品需求");
        }

        // 更新评估记录的决策信息
        evaluation.setDecision(request.getDecision());
        evaluation.setDecisionBy(decisionBy);
        evaluation.setDecisionAt(LocalDateTime.now());
        evaluation.setDecisionReason(request.getDecisionReason());
        evaluationMapper.updateById(evaluation);

        // 根据决策更新需求状态
        if ("通过".equals(request.getDecision())) {
            requirement.setStatus("待设计");
        } else if ("拒绝".equals(request.getDecision())) {
            requirement.setStatus("已拒绝");
        } else if ("转产品需求".equals(request.getDecision())) {
            // 转产品需求：修改需求类型，清空项目ID和客户联系人ID，状态改为待设计
            requirement.setType("产品需求");
            requirement.setProjectId(null);
            requirement.setCustomerContactId(null);
            requirement.setStatus("待设计");
        }

        requirementMapper.updateById(requirement);

        return evaluation;
    }
}
