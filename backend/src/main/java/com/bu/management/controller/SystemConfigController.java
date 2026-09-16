package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.SystemConfigGroupRequest;
import com.bu.management.service.SystemConfigService;
import com.bu.management.vo.Result;
import com.bu.management.vo.SystemConfigGroupSummary;
import com.bu.management.vo.SystemConfigGroupView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通用系统配置管理（非连接类配置：周报、同步、任务参数等）。
 * 外部系统连接（云效/工时/OA/语雀/邮件/DeepSeek/GLM/企业微信）统一在「连接器管理」维护，
 * 连接类配置组已退役，不再出现在这里。
 */
@RestController
@RequestMapping("/api/system/configs")
@RequiredArgsConstructor
@RequirePermission({"system:config:list"})
public class SystemConfigController {

    private final SystemConfigService configService;

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
}
