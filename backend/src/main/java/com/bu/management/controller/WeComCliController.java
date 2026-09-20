package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.integration.WeComCliClient;
import com.bu.management.service.WeComCliService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业微信机器人通道（wecom-cli）管理端：授权、品类体检、真实业务取数。
 * 配置仍收口在「连接器管理 → 企业微信」卡片（Bot ID/Bot Secret），本控制器只负责授权动作与数据读取。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Tag(name = "企业微信机器人通道", description = "wecom-cli 授权与业务能力（待办/通讯录/会议/文档/消息/邮件）")
@RestController
@RequestMapping("/api/wecom-cli")
@RequiredArgsConstructor
@RequirePermission({"system:config:edit"})
public class WeComCliController {

    private final WeComCliService service;

    // ==================== 授权 ====================

    @GetMapping("/status")
    @Operation(summary = "机器人通道状态")
    public Result<Map<String, Object>> status() {
        return Result.success(service.status());
    }

    @PostMapping("/authorize")
    @Operation(summary = "用连接器保存的 Bot 凭证授权")
    public Result<Map<String, Object>> authorize() {
        return Result.success(service.authorizeWithStoredCredentials());
    }

    @PostMapping("/auth/qrcode")
    @Operation(summary = "发起扫码授权，返回二维码")
    public Result<Map<String, Object>> qrcode() {
        return Result.success(service.startQrAuthorization());
    }

    @GetMapping("/auth/poll")
    @Operation(summary = "查询扫码授权进度")
    public Result<Map<String, Object>> poll(@RequestParam String sessionId) {
        return Result.success(service.pollQrAuthorization(sessionId));
    }

    @GetMapping("/capabilities")
    @Operation(summary = "品类授权体检")
    public Result<List<WeComCliClient.Capability>> capabilities(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return Result.success(service.capabilities(refresh));
    }

    // ==================== 真实业务数据 ====================

    @GetMapping("/todos")
    @Operation(summary = "待办列表")
    public Result<List<Map<String, Object>>> todos(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) List<String> status) {
        return Result.success(service.listTodos(limit, status));
    }

    @PostMapping("/todos")
    @Operation(summary = "创建待办")
    public Result<Object> createTodo(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> followerIds = body.get("followerIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : null;
        return Result.success(service.createTodo(
                text(body, "title"), text(body, "description"), text(body, "deadline"), followerIds));
    }

    @PostMapping("/todos/finish")
    @Operation(summary = "完成待办（批量）")
    public Result<Object> finishTodos(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = body.get("todoIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return Result.success(service.finishTodos(ids));
    }

    @GetMapping("/schedules")
    @Operation(summary = "日程列表（企微限制只能查当天前后 30 天内）")
    public Result<List<Map<String, Object>>> schedules(
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(service.listSchedules(beginTime, endTime, limit));
    }

    @GetMapping("/contacts")
    @Operation(summary = "通讯录搜索")
    public Result<List<Map<String, Object>>> contacts(@RequestParam String keyword) {
        return Result.success(service.searchContacts(keyword));
    }

    @GetMapping("/docs")
    @Operation(summary = "文档搜索")
    public Result<List<Map<String, Object>>> docs(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(service.searchDocs(keyword, limit));
    }

    @GetMapping("/docs/content")
    @Operation(summary = "读取文档内容")
    public Result<Map<String, Object>> docContent(@RequestParam String docId) {
        return Result.success(Map.of("content", service.readDoc(docId)));
    }

    @PostMapping("/messages")
    @Operation(summary = "发送机器人消息")
    public Result<Object> sendMessage(@RequestBody Map<String, Object> body) {
        return Result.success(service.sendMessage(text(body, "chatId"), text(body, "text")));
    }

    @GetMapping("/mails")
    @Operation(summary = "邮件检索（只读）")
    public Result<List<Map<String, Object>>> mails(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(service.searchMail(keyword, limit));
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
