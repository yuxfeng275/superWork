package com.bu.management.controller;

import com.bu.management.dto.AcceptRequirementDTO;
import com.bu.management.dto.CreateRequirementDeliveryDTO;
import com.bu.management.entity.RequirementDelivery;
import com.bu.management.service.RequirementDeliveryService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求交付控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "需求交付", description = "需求交付管理接口")
@RestController
@RequestMapping("/api/requirement-deliveries")
@RequiredArgsConstructor
public class RequirementDeliveryController {

    private final RequirementDeliveryService deliveryService;

    /**
     * 创建需求交付
     */
    @Operation(summary = "创建需求交付", description = "交付需求，需求状态必须为已上线，交付后自动流转到已交付")
    @PostMapping
    public Result<RequirementDelivery> createDelivery(@RequestBody CreateRequirementDeliveryDTO dto) {
        RequirementDelivery delivery = deliveryService.createDelivery(dto);
        return Result.success(delivery);
    }

    /**
     * 验收需求
     */
    @Operation(summary = "验收需求", description = "验收需求，需求状态必须为已交付，验收后自动流转到已验收")
    @PostMapping("/{requirementId}/accept")
    public Result<RequirementDelivery> acceptRequirement(
            @Parameter(description = "需求ID") @PathVariable Long requirementId,
            @RequestBody AcceptRequirementDTO dto) {
        RequirementDelivery delivery = deliveryService.acceptRequirement(requirementId, dto);
        return Result.success(delivery);
    }

    /**
     * 查询需求交付
     */
    @Operation(summary = "查询需求交付", description = "根据需求ID查询交付记录")
    @GetMapping("/{requirementId}")
    public Result<RequirementDelivery> getDelivery(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        RequirementDelivery delivery = deliveryService.getDeliveryByRequirementId(requirementId);
        return Result.success(delivery);
    }
}
