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
 * 模型可自带协议 / 地址 / API Key；未填时回落同名连接器。
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

    /**
     * 拉取远程模型列表（OpenAI 兼容 GET /models）。
     * 第三方中转站不知道模型 ID 时，填好地址和 Key 一键拉取可选模型。
     */
    @PostMapping("/remote-models")
    public Result<List<String>> remoteModels(
            @RequestBody AiModelConfigService.RemoteModelsRequest request) {
        return Result.success(modelConfigService.fetchRemoteModels(request));
    }

    /** 测试已存模型：地址 + 凭据 + 模型名全链路验证。 */
    @PostMapping("/{id}/test")
    public Result<AiModelConfigService.ModelTestResult> testSaved(@PathVariable Long id) {
        return Result.success(modelConfigService.testModel(
                new AiModelConfigService.ModelTestRequest(id, null, null, null, null, null)));
    }

    /** 测试表单中的模型（未保存）：按表单地址/Key/提供方回落组装探测目标。 */
    @PostMapping("/test")
    public Result<AiModelConfigService.ModelTestResult> testDraft(
            @RequestBody AiModelConfigService.ModelTestRequest request) {
        return Result.success(modelConfigService.testModel(request));
    }
}
