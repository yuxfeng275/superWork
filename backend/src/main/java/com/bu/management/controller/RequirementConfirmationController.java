package com.bu.management.controller;

import com.bu.management.dto.CreateRequirementConfirmationDTO;
import com.bu.management.entity.RequirementConfirmation;
import com.bu.management.service.RequirementConfirmationService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求确认控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "需求确认", description = "需求确认管理接口")
@RestController
@RequestMapping("/api/requirement-confirmations")
@RequiredArgsConstructor
public class RequirementConfirmationController {

    private final RequirementConfirmationService confirmationService;

    /**
     * 创建需求确认
     */
    @Operation(summary = "创建需求确认", description = "确认需求，需求状态必须为待确认，确认后自动流转到开发中")
    @PostMapping
    public Result<RequirementConfirmation> createConfirmation(@RequestBody CreateRequirementConfirmationDTO dto) {
        RequirementConfirmation confirmation = confirmationService.createConfirmation(dto);
        return Result.success(confirmation);
    }

    /**
     * 查询需求确认
     */
    @Operation(summary = "查询需求确认", description = "根据需求ID查询确认记录")
    @GetMapping("/{requirementId}")
    public Result<RequirementConfirmation> getConfirmation(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        RequirementConfirmation confirmation = confirmationService.getConfirmationByRequirementId(requirementId);
        return Result.success(confirmation);
    }
}
