package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.User;
import com.bu.management.service.UserService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Tag(name = "用户管理", description = "用户的增删改查接口")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 创建用户
     */
    @Operation(summary = "创建用户", description = "创建新用户")
    @PostMapping
    public Result<User> create(@Valid @RequestBody UserRequest request) {
        User user = userService.create(request);
        return Result.success("创建成功", user);
    }

    /**
     * 更新用户
     */
    @Operation(summary = "更新用户", description = "更新用户信息")
    @PutMapping("/{id}")
    public Result<User> update(
            @Parameter(description = "用户ID") @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {
        User user = userService.update(id, request);
        return Result.success("更新成功", user);
    }

    /**
     * 禁用/启用用户
     */
    @Operation(summary = "禁用/启用用户", description = "更新用户状态")
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(
            @Parameter(description = "用户ID") @PathVariable Long id,
            @Parameter(description = "状态：1=启用，0=禁用") @RequestParam Integer status) {
        userService.updateStatus(id, status);
        return Result.success("状态更新成功", null);
    }

    /**
     * 获取用户详情
     */
    @Operation(summary = "获取用户详情", description = "根据ID获取用户详情")
    @GetMapping("/{id}")
    public Result<User> getById(@Parameter(description = "用户ID") @PathVariable Long id) {
        User user = userService.getById(id);
        return Result.success(user);
    }

    /**
     * 分页查询用户列表
     */
    @Operation(summary = "分页查询用户", description = "分页查询用户列表，支持用户名、姓名、角色和状态筛选")
    @GetMapping
    public Result<Page<User>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "用户名（模糊查询）") @RequestParam(required = false) String username,
            @Parameter(description = "真实姓名（模糊查询）") @RequestParam(required = false) String realName,
            @Parameter(description = "角色") @RequestParam(required = false) String role,
            @Parameter(description = "状态：1=启用，0=禁用") @RequestParam(required = false) Integer status) {
        Page<User> result = userService.list(page, size, username, realName, role, status);
        return Result.success(result);
    }
}
