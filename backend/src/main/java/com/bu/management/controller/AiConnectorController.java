package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.AiConnectorRegistryService;
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
 * AI 连接器注册表管理端：通用化新建/编辑/测试/删除外部系统连接。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@RestController
@RequestMapping("/api/ai/connectors")
@RequiredArgsConstructor
@RequirePermission({"system:config:edit"})
public class AiConnectorController {

    private final AiConnectorRegistryService registryService;

    @GetMapping
    public Result<List<AiConnectorRegistryService.ConnectorView>> list() {
        return Result.success(registryService.list());
    }

    @GetMapping("/{id}")
    public Result<AiConnectorRegistryService.ConnectorView> get(@PathVariable Long id) {
        return Result.success(registryService.get(id));
    }

    @PostMapping
    public Result<AiConnectorRegistryService.ConnectorView> create(
            @Valid @RequestBody AiConnectorRegistryService.ConnectorSaveRequest request) {
        return Result.success(registryService.create(request));
    }

    @PutMapping("/{id}")
    public Result<AiConnectorRegistryService.ConnectorView> update(
            @PathVariable Long id,
            @Valid @RequestBody AiConnectorRegistryService.ConnectorSaveRequest request) {
        return Result.success(registryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        registryService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<AiConnectorRegistryService.ConnectorView> test(@PathVariable Long id) {
        return Result.success(registryService.test(id));
    }
}
