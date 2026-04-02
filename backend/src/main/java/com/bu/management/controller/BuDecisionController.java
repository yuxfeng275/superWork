package com.bu.management.controller;

import com.bu.management.dto.BuDecisionRequest;
import com.bu.management.entity.RequirementEvaluation;
import com.bu.management.service.BuDecisionService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * BU决策控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "BU决策管理", description = "BU负责人对需求评估进行决策")
@RestController
@RequestMapping("/api/bu-decisions")
@RequiredArgsConstructor
public class BuDecisionController {

    private final BuDecisionService buDecisionService;

    /**
     * 提交BU决策
     */
    @Operation(summary = "提交BU决策", description = "BU负责人对需求评估进行决策（通过/拒绝/转产品需求）")
    @PostMapping
    public Result<RequirementEvaluation> makeDecision(
            @Valid @RequestBody BuDecisionRequest request,
            Authentication authentication) {
        // 从认证信息中获取当前用户ID（简化处理）
        Long decisionBy = 1L; // TODO: 从 authentication 中获取真实用户ID
        RequirementEvaluation evaluation = buDecisionService.makeDecision(request, decisionBy);
        return Result.success("决策提交成功", evaluation);
    }
}
