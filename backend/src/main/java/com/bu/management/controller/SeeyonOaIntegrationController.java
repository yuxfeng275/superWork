package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
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

    // ==================== 会话授权（网页通道） ====================

    @GetMapping("/session")
    @Operation(summary = "OA 网页会话授权状态（REST 被拦时的取数通道）")
    public Result<com.bu.management.integration.SeeyonOaWebChannel.SessionStatus> sessionStatus() {
        return Result.success(integrationService.sessionStatus());
    }

    public record SessionAuthRequest(String cookie) {}

    @PostMapping("/session")
    @Operation(summary = "保存 OA 网页会话授权（粘贴浏览器 JSESSIONID，一次授权会话复用）")
    public Result<com.bu.management.integration.SeeyonOaWebChannel.SessionStatus> authorize(
            @RequestBody SessionAuthRequest request) {
        return Result.success(integrationService.authorize(request == null ? null : request.cookie()));
    }
    /** 获取验证码挑战（图片 base64 + challengeId），用于「账号密码 + 验证码」自助授权。 */
    @GetMapping("/session/captcha")
    @Operation(summary = "获取 OA 登录验证码（自助授权）")
    public Result<com.bu.management.integration.SeeyonOaWebChannel.CaptchaChallenge> captcha() {
        return Result.success(integrationService.captchaChallenge());
    }

    public record SessionLoginRequest(String challengeId, String captcha) {}

    /** 账号密码 + 验证码自助授权（challengeId 可选；不需要验证码时传空即可）。 */
    @PostMapping("/session/login")
    @Operation(summary = "OA 账号密码 + 验证码自助授权")
    public Result<com.bu.management.integration.SeeyonOaWebChannel.SessionStatus> login(
            @RequestBody SessionLoginRequest request) {
        return Result.success(integrationService.loginWithPassword(
                request == null ? null : request.challengeId(),
                request == null ? null : request.captcha()));
    }

    /** 自动授权：OA 不强制验证码时直接登录成功。 */
    @PostMapping("/session/auto")
    @Operation(summary = "OA 自动授权（无需验证码时）")
    public Result<com.bu.management.integration.SeeyonOaWebChannel.SessionStatus> autoLogin() {
        return Result.success(integrationService.tryAutoLogin());
    }

    @DeleteMapping("/session")
    @Operation(summary = "清除 OA 网页会话授权")
    public Result<Void> clearSession() {
        integrationService.clearSession();
        return Result.success();
    }

    // ==================== 待办审批 ====================

    public record ApproveRequest(String action) {}

    @PostMapping("/affairs/{affairId}/approve")
    @Operation(summary = "审批 OA 事项（action=同意/不同意，默认同意）")
    public Result<String> approve(@PathVariable String affairId, @RequestBody(required = false) ApproveRequest request) {
        String action = request == null || request.action() == null ? "approve" : request.action();
        return Result.success(integrationService.approve(affairId, action));
    }

    public record BatchApproveRequest(java.util.List<String> affairIds, String action) {}

    @PostMapping("/affairs/batch-approve")
    @Operation(summary = "批量审批 OA 待办事项（逐项执行并返回每项结果）")
    public Result<java.util.List<Map<String, Object>>> batchApprove(@RequestBody BatchApproveRequest request) {
        if (request == null || request.affairIds() == null || request.affairIds().isEmpty()) {
            throw new IllegalArgumentException("affairIds 不能为空");
        }
        String action = request.action() == null ? "approve" : request.action();
        return Result.success(integrationService.batchApprove(request.affairIds(), action));
    }

    // ==================== 数据同步 ====================

    @PostMapping("/sync")
    @Operation(summary = "同步 OA 数据到本地系统")
    public Result<List<String>> sync() {
        return Result.success(integrationService.syncAll());
    }
}