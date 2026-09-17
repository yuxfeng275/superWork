package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.ConnectorRegistryService;
import com.bu.management.service.ConnectorTestDispatcher;
import com.bu.management.vo.Result;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接器管理端（唯一入口）：外部系统连接的查看/新建/编辑/测试/删除。
 * 覆盖内置连接器（云效/工时/OA/语雀/邮件/DeepSeek/GLM/企业微信）与自建通用连接器。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@RestController
@RequestMapping("/api/connectors")
@RequiredArgsConstructor
@RequirePermission({"system:config:edit"})
public class ConnectorController {

    private final ConnectorRegistryService registryService;
    private final ConnectorTestDispatcher testDispatcher;

    @GetMapping
    public Result<List<ConnectorRegistryService.ConnectorView>> list() {
        return Result.success(registryService.list());
    }

    /** 连接器状态列表（AI 助手面板与管理页共用的唯一口径）。 */
    @GetMapping("/status")
    public Result<List<ConnectorRegistryService.ConnectorStatus>> statuses() {
        return Result.success(registryService.statuses());
    }

    @GetMapping("/{id}")
    public Result<ConnectorRegistryService.ConnectorView> get(@PathVariable Long id) {
        return Result.success(registryService.get(id));
    }

    @PostMapping
    public Result<ConnectorRegistryService.ConnectorView> create(
            @Valid @RequestBody ConnectorRegistryService.ConnectorSaveRequest request) {
        return Result.success(registryService.create(request));
    }

    @PutMapping("/{id}")
    public Result<ConnectorRegistryService.ConnectorView> update(
            @PathVariable Long id,
            @Valid @RequestBody ConnectorRegistryService.ConnectorSaveRequest request) {
        return Result.success(registryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        registryService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<ConnectorRegistryService.ConnectorView> test(@PathVariable Long id) {
        return Result.success(testDispatcher.test(id));
    }
}
