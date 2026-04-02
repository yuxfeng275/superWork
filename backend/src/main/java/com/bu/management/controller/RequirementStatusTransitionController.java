package com.bu.management.controller;

import com.bu.management.service.RequirementStatusTransitionService;
import com.bu.management.vo.RequirementStatusTransition;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求状态流转控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "需求状态流转", description = "需求状态流转查询和验证接口")
@RestController
@RequestMapping("/api/requirement-transitions")
@RequiredArgsConstructor
public class RequirementStatusTransitionController {

    private final RequirementStatusTransitionService transitionService;

    /**
     * 获取需求状态流转信息
     */
    @Operation(summary = "获取需求状态流转信息", description = "获取需求的当前状态和流转历史信息")
    @GetMapping("/{requirementId}")
    public Result<RequirementStatusTransition> getTransitionInfo(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        RequirementStatusTransition transition = transitionService.getTransitionInfo(requirementId);
        return Result.success(transition);
    }

    /**
     * 验证状态流转是否合法
     */
    @Operation(summary = "验证状态流转", description = "验证需求是否可以流转到目标状态")
    @GetMapping("/{requirementId}/can-transition")
    public Result<Boolean> canTransitionTo(
            @Parameter(description = "需求ID") @PathVariable Long requirementId,
            @Parameter(description = "目标状态") @RequestParam String targetStatus) {
        boolean canTransition = transitionService.canTransitionTo(requirementId, targetStatus);
        return Result.success(canTransition);
    }
}
