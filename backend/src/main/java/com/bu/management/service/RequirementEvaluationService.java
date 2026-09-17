package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RequirementEvaluationRequest;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementEvaluation;
import com.bu.management.mapper.RequirementEvaluationMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 需求评估服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class RequirementEvaluationService {

    private final RequirementEvaluationMapper evaluationMapper;
    private final RequirementMapper requirementMapper;

    /**
     * 提交需求评估
     */
    @Transactional(rollbackFor = Exception.class)
    public RequirementEvaluation submitEvaluation(RequirementEvaluationRequest request, Long evaluatorId) {
        // 验证需求是否存在
        Requirement requirement = requirementMapper.selectById(request.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 验证需求状态（只有待评估或评估中的需求才能提交评估）
        if (!"待评估".equals(requirement.getStatus()) && !"评估中".equals(requirement.getStatus())) {
            throw new RuntimeException("只有待评估或评估中的需求才能提交评估");
        }

        // 项目需求必须提供预估报价
        if ("项目需求".equals(requirement.getType()) && request.getEstimatedCost() == null) {
            throw new RuntimeException("项目需求必须提供预估报价");
        }

        // 检查是否已有评估记录
        LambdaQueryWrapper<RequirementEvaluation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementEvaluation::getRequirementId, request.getRequirementId());
        RequirementEvaluation existingEvaluation = evaluationMapper.selectOne(wrapper);

        RequirementEvaluation evaluation;
        if (existingEvaluation != null) {
            // 更新已有评估
            evaluation = existingEvaluation;
            evaluation.setIsFeasible(request.getIsFeasible());
            evaluation.setFeasibilityDesc(request.getFeasibilityDesc());
            evaluation.setEstimatedWorkload(request.getEstimatedWorkload());
            evaluation.setEstimatedCost(request.getEstimatedCost());
            evaluation.setWorkBreakdown(request.getWorkBreakdown());
            evaluation.setSuggestProduct(request.getSuggestProduct() != null ? request.getSuggestProduct() : 0);
            evaluation.setEvaluatorId(evaluatorId);
            evaluation.setEvaluatedAt(LocalDateTime.now());
            evaluationMapper.updateById(evaluation);
        } else {
            // 创建新评估
            evaluation = new RequirementEvaluation();
            evaluation.setRequirementId(request.getRequirementId());
            evaluation.setIsFeasible(request.getIsFeasible());
            evaluation.setFeasibilityDesc(request.getFeasibilityDesc());
            evaluation.setEstimatedWorkload(request.getEstimatedWorkload());
            evaluation.setEstimatedCost(request.getEstimatedCost());
            evaluation.setWorkBreakdown(request.getWorkBreakdown());
            evaluation.setSuggestProduct(request.getSuggestProduct() != null ? request.getSuggestProduct() : 0);
            evaluation.setEvaluatorId(evaluatorId);
            evaluation.setEvaluatedAt(LocalDateTime.now());
            evaluationMapper.insert(evaluation);
        }

        // 更新需求状态为"评估中"
        if ("待评估".equals(requirement.getStatus())) {
            requirement.setStatus("评估中");
            requirementMapper.updateById(requirement);
        }

        return evaluation;
    }

    /**
     * 查询需求评估
     */
    public RequirementEvaluation getByRequirementId(Long requirementId) {
        // 验证需求是否存在
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        LambdaQueryWrapper<RequirementEvaluation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementEvaluation::getRequirementId, requirementId);
        RequirementEvaluation evaluation = evaluationMapper.selectOne(wrapper);

        if (evaluation == null) {
            throw new RuntimeException("该需求尚未评估");
        }

        return evaluation;
    }

    /**
     * 获取评估详情
     */
    public RequirementEvaluation getById(Long id) {
        RequirementEvaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null) {
            throw new RuntimeException("评估记录不存在");
        }
        return evaluation;
    }
}
