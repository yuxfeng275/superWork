package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RequirementEvaluationRequest;
import com.bu.management.entity.RequirementEvaluation;
import com.bu.management.service.RequirementEvaluationService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求评估控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "需求评估管理", description = "需求评估的提交和查询接口")
@RestController
@RequestMapping("/api/requirement-evaluations")
@RequiredArgsConstructor
public class RequirementEvaluationController {

    private final RequirementEvaluationService evaluationService;

    /**
     * 提交需求评估
     */
    @Operation(summary = "提交需求评估", description = "提交或更新需求评估信息")
    @PostMapping
    @RequirePermission({"requirement:edit"})
    public Result<RequirementEvaluation> submitEvaluation(
            @Valid @RequestBody RequirementEvaluationRequest request,
            @RequestAttribute("userId") Long evaluatorId) {
        RequirementEvaluation evaluation = evaluationService.submitEvaluation(request, evaluatorId);
        return Result.success("评估提交成功", evaluation);
    }

    /**
     * 根据需求ID查询评估
     */
    @Operation(summary = "根据需求ID查询评估", description = "获取指定需求的评估信息")
    @GetMapping("/by-requirement/{requirementId}")
    @RequirePermission({"requirement:list"})
    public Result<RequirementEvaluation> getByRequirementId(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        RequirementEvaluation evaluation = evaluationService.getByRequirementId(requirementId);
        return Result.success(evaluation);
    }

    /**
     * 获取评估详情
     */
    @Operation(summary = "获取评估详情", description = "根据评估ID获取评估详情")
    @GetMapping("/{id}")
    @RequirePermission({"requirement:list"})
    public Result<RequirementEvaluation> getById(
            @Parameter(description = "评估ID") @PathVariable Long id) {
        RequirementEvaluation evaluation = evaluationService.getById(id);
        return Result.success(evaluation);
    }
}
