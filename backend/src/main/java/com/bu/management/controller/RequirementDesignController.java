package com.bu.management.controller;

import com.bu.management.dto.CreateRequirementDesignDTO;
import com.bu.management.dto.UpdateRequirementDesignDTO;
import com.bu.management.entity.RequirementDesign;
import com.bu.management.service.RequirementDesignService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 需求设计控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "需求设计", description = "需求设计管理接口")
@RestController
@RequestMapping("/api/requirement-designs")
@RequiredArgsConstructor
public class RequirementDesignController {

    private final RequirementDesignService designService;

    /**
     * 创建需求设计
     */
    @Operation(summary = "创建需求设计", description = "为需求创建设计记录，需求状态必须为待设计")
    @PostMapping
    public Result<RequirementDesign> createDesign(@RequestBody CreateRequirementDesignDTO dto) {
        RequirementDesign design = designService.createDesign(dto);
        return Result.success(design);
    }

    /**
     * 更新需求设计
     */
    @Operation(summary = "更新需求设计", description = "更新需求设计状态，全部完成后自动流转到待确认")
    @PutMapping("/{requirementId}")
    public Result<RequirementDesign> updateDesign(
            @Parameter(description = "需求ID") @PathVariable Long requirementId,
            @RequestBody UpdateRequirementDesignDTO dto) {
        RequirementDesign design = designService.updateDesign(requirementId, dto);
        return Result.success(design);
    }

    /**
     * 查询需求设计
     */
    @Operation(summary = "查询需求设计", description = "根据需求ID查询设计记录")
    @GetMapping("/{requirementId}")
    public Result<RequirementDesign> getDesign(
            @Parameter(description = "需求ID") @PathVariable Long requirementId) {
        RequirementDesign design = designService.getDesignByRequirementId(requirementId);
        return Result.success(design);
    }
}
