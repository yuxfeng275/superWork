package com.bu.management.integration;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会议总结 LLM 客户端：复用「模型管理」中勾选「摘要」用途的模型，
 * 与周报共用 {@link DigestLlmClient} 的多模型兜底（首选额度/鉴权失败时自动换下一个）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingSummaryClient {

    private final DigestLlmClient digestLlmClient;

    /** 最近一次成功调用实际使用的模型（provider/model），用于 generation_model 落库。 */
    private volatile String lastUsed = "";

    public String describeModel() {
        return lastUsed.isEmpty() ? digestLlmClient.describeCandidates() : lastUsed;
    }

    public JsonNode chatJson(String systemPrompt, String userInput) {
        DigestLlmClient.ChatResult result = digestLlmClient.chat(systemPrompt, userInput);
        lastUsed = result.providerCode() + "/" + result.model();
        return result.json();
    }
}
