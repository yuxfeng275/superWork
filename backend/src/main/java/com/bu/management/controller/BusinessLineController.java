package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.BusinessLineRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.service.BusinessLineService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 业务线控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "业务线管理", description = "业务线的增删改查接口")
@RestController
@RequestMapping("/api/business-lines")
@RequiredArgsConstructor
public class BusinessLineController {

    private final BusinessLineService businessLineService;

    /**
     * 创建业务线
     */
    @Operation(summary = "创建业务线", description = "创建新的业务线")
    @PostMapping
    public Result<BusinessLine> create(@Valid @RequestBody BusinessLineRequest request) {
        BusinessLine businessLine = businessLineService.create(request);
        return Result.success("创建成功", businessLine);
    }

    /**
     * 更新业务线
     */
    @Operation(summary = "更新业务线", description = "更新业务线信息")
    @PutMapping("/{id}")
    public Result<BusinessLine> update(
            @Parameter(description = "业务线ID") @PathVariable Long id,
            @Valid @RequestBody BusinessLineRequest request) {
        BusinessLine businessLine = businessLineService.update(id, request);
        return Result.success("更新成功", businessLine);
    }

    /**
     * 删除业务线
     */
    @Operation(summary = "删除业务线", description = "删除指定业务线")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "业务线ID") @PathVariable Long id) {
        businessLineService.delete(id);
        return Result.success("删除成功", null);
    }

    /**
     * 获取业务线详情
     */
    @Operation(summary = "获取业务线详情", description = "根据ID获取业务线详情")
    @GetMapping("/{id}")
    public Result<BusinessLine> getById(@Parameter(description = "业务线ID") @PathVariable Long id) {
        BusinessLine businessLine = businessLineService.getById(id);
        return Result.success(businessLine);
    }

    /**
     * 分页查询业务线列表
     */
    @Operation(summary = "分页查询业务线", description = "分页查询业务线列表，支持名称搜索和状态筛选")
    @GetMapping
    public Result<Page<BusinessLine>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "业务线名称（模糊查询）") @RequestParam(required = false) String name,
            @Parameter(description = "状态：1=启用，0=禁用") @RequestParam(required = false) Integer status) {
        Page<BusinessLine> result = businessLineService.list(page, size, name, status);
        return Result.success(result);
    }
}
