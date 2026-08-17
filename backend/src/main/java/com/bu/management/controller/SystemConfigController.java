package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.SystemConfigGroupRequest;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.service.EmailIntegrationConfigService;
import com.bu.management.service.SystemConfigService;
import com.bu.management.vo.EmailIntegrationTestResponse;
import com.bu.management.vo.Result;
import com.bu.management.vo.SystemConfigGroupSummary;
import com.bu.management.vo.SystemConfigGroupView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/system/configs")
@RequiredArgsConstructor
@RequirePermission({"system:config:list"})
public class SystemConfigController {
    private final SystemConfigService configService;
    private final DeepSeekDigestClient deepSeekClient;
    private final WeComClient weComClient;

    @GetMapping
    public Result<List<SystemConfigGroupSummary>> listGroups() {
        return Result.success(configService.listGroups());
    }

    @GetMapping("/{groupCode}")
    public Result<SystemConfigGroupView> getGroup(@PathVariable String groupCode) {
        return Result.success(configService.getGroup(groupCode));
    }

    @PutMapping("/{groupCode}")
    @RequirePermission({"system:config:edit"})
    public Result<SystemConfigGroupView> saveGroup(
            @PathVariable String groupCode,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody SystemConfigGroupRequest request) {
        return Result.success(configService.saveGroup(groupCode, request, userId));
    }

    @PostMapping("/email-integration/deepseek/test")
    @RequirePermission({"system:config:edit"})
    public Result<EmailIntegrationTestResponse> testDeepSeek() {
        return test(deepSeekClient::testConnection, "DeepSeek");
    }

    @PostMapping("/email-integration/wecom/test")
    @RequirePermission({"system:config:edit"})
    public Result<EmailIntegrationTestResponse> testWeCom() {
        return test(weComClient::testConnection, "企业微信");
    }

    private Result<EmailIntegrationTestResponse> test(Runnable operation, String name) {
        LocalDateTime testedAt = LocalDateTime.now();
        try {
            operation.run();
            return Result.success(new EmailIntegrationTestResponse(true, "连接成功", testedAt));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) message = name + "连接失败";
            message = message.substring(0, Math.min(500, message.length()));
            return Result.success(new EmailIntegrationTestResponse(false, message, testedAt));
        }
    }
}
