package com.bu.management.controller;

import com.bu.management.service.AiModelConfigService;
import com.bu.management.vo.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手只读选项端点：登录即可读，供普通成员在 AI 助手里选模型。
 * 只返回 assistantEnabled=1 且 enabled=1 且接入就绪的模型选项（provider / model / label），
 * 不含地址、协议、API Key 等敏感配置；管理端仍走 {@link AiModelController}。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@RestController
@RequestMapping("/api/ai/assistant")
@RequiredArgsConstructor
public class AiAssistantModelController {

    private final AiModelConfigService modelConfigService;

    /** 助手可用模型选项（登录即可用，不校验 system:config:edit）。 */
    @GetMapping("/models")
    public Result<List<AiModelConfigService.ModelOption>> models() {
        return Result.success(modelConfigService.listAvailableModels());
    }
}
