package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.AiModelConfigService;
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
 * AI 模型管理端（唯一入口）：模型清单、用途（助手 / 摘要）、默认模型、启停。
 * 提供方的地址与凭据在「连接器管理」维护，本接口只管模型本身。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@RestController
@RequestMapping("/api/ai/models")
@RequiredArgsConstructor
@RequirePermission({"system:config:edit"})
public class AiModelController {

    private final AiModelConfigService modelConfigService;

    @GetMapping
    public Result<List<AiModelConfigService.ModelView>> list() {
        return Result.success(modelConfigService.list());
    }

    @PostMapping
    public Result<AiModelConfigService.ModelView> create(
            @Valid @RequestBody AiModelConfigService.ModelSaveRequest request) {
        return Result.success(modelConfigService.create(request));
    }

    @PutMapping("/{id}")
    public Result<AiModelConfigService.ModelView> update(
            @PathVariable Long id,
            @Valid @RequestBody AiModelConfigService.ModelSaveRequest request) {
        return Result.success(modelConfigService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.success();
    }
}
