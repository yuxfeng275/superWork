package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CustomerContactRequest;
import com.bu.management.entity.CustomerContact;
import com.bu.management.service.CustomerContactService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 客户联系人控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "客户联系人管理", description = "客户联系人的增删改查接口")
@RestController
@RequestMapping("/api/customer-contacts")
@RequiredArgsConstructor
public class CustomerContactController {

    private final CustomerContactService customerContactService;

    /**
     * 创建客户联系人
     */
    @Operation(summary = "创建客户联系人", description = "创建新的客户联系人")
    @PostMapping
    public Result<CustomerContact> create(@Valid @RequestBody CustomerContactRequest request) {
        CustomerContact contact = customerContactService.create(request);
        return Result.success("创建成功", contact);
    }

    /**
     * 更新客户联系人
     */
    @Operation(summary = "更新客户联系人", description = "更新客户联系人信息")
    @PutMapping("/{id}")
    public Result<CustomerContact> update(
            @Parameter(description = "联系人ID") @PathVariable Long id,
            @Valid @RequestBody CustomerContactRequest request) {
        CustomerContact contact = customerContactService.update(id, request);
        return Result.success("更新成功", contact);
    }

    /**
     * 删除客户联系人
     */
    @Operation(summary = "删除客户联系人", description = "删除指定客户联系人")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "联系人ID") @PathVariable Long id) {
        customerContactService.delete(id);
        return Result.success("删除成功", null);
    }

    /**
     * 获取客户联系人详情
     */
    @Operation(summary = "获取客户联系人详情", description = "根据ID获取客户联系人详情")
    @GetMapping("/{id}")
    public Result<CustomerContact> getById(@Parameter(description = "联系人ID") @PathVariable Long id) {
        CustomerContact contact = customerContactService.getById(id);
        return Result.success(contact);
    }

    /**
     * 分页查询客户联系人列表
     */
    @Operation(summary = "分页查询客户联系人", description = "分页查询客户联系人列表，支持项目、姓名和状态筛选")
    @GetMapping
    public Result<Page<CustomerContact>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "项目ID") @RequestParam(required = false) Long projectId,
            @Parameter(description = "联系人姓名（模糊查询）") @RequestParam(required = false) String name,
            @Parameter(description = "是否有效：1=是，0=否") @RequestParam(required = false) Integer isActive) {
        Page<CustomerContact> result = customerContactService.list(page, size, projectId, name, isActive);
        return Result.success(result);
    }
}
