package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.SeeyonOaConfigRequest;
import com.bu.management.service.SeeyonOaIntegrationService;
import com.bu.management.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "OA 集成", description = "致远互联 OA 系统集成：配置、数据查询、同步")
@RestController
@RequestMapping("/api/seeyon-oa")
@RequiredArgsConstructor
@RequirePermission({"seeyon-oa:manage"})
public class SeeyonOaIntegrationController {

    private final SeeyonOaIntegrationService integrationService;

    // ==================== 配置管理 ====================

    @GetMapping("/status")
    @Operation(summary = "获取 OA 集成状态")
    public Result<Map<String, Object>> getStatus() {
        return Result.success(integrationService.getStatus());
    }

    @PutMapping("/config")
    @Operation(summary = "保存 OA 集成配置")
    public Result<Map<String, Object>> saveConfig(
            @Valid @RequestBody SeeyonOaConfigRequest request,
            @RequestAttribute("userId") Long userId) {
        return Result.success("OA 配置已保存", integrationService.saveConfig(request, userId));
    }

    @PostMapping("/connection-test")
    @Operation(summary = "测试 OA 连接")
    public Result<SeeyonOaConnectionTestResponse> testConnection() {
        return Result.success(integrationService.testConnection());
    }

    // ==================== 数据查询 ====================

    @GetMapping("/members")
    @Operation(summary = "获取 OA 人员列表")
    public Result<List<SeeyonOaMemberOption>> listMembers(
            @RequestParam(required = false) String departmentId) {
        if (departmentId != null && !departmentId.isEmpty()) {
            return Result.success(integrationService.listMembersByDepartment(departmentId));
        }
        return Result.success(integrationService.listMembers());
    }

    @GetMapping("/departments")
    @Operation(summary = "获取 OA 部门列表")
    public Result<List<SeeyonOaDepartmentOption>> listDepartments() {
        return Result.success(integrationService.listDepartments());
    }

    @GetMapping("/affairs/pending")
    @Operation(summary = "获取 OA 待办事项")
    public Result<List<Map<String, Object>>> listPendingAffairs() {
        return Result.success(integrationService.listPendingAffairs());
    }

    @GetMapping("/affairs/done")
    @Operation(summary = "获取 OA 已办事项")
    public Result<List<Map<String, Object>>> listDoneAffairs() {
        return Result.success(integrationService.listDoneAffairs());
    }

    // ==================== 数据同步 ====================

    @PostMapping("/sync")
    @Operation(summary = "同步 OA 数据到本地系统")
    public Result<List<String>> sync() {
        return Result.success(integrationService.syncAll());
    }
}