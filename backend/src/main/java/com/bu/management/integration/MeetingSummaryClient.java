package com.bu.management.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Consumer;
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
        return chatJson(systemPrompt, userInput, json -> { });
    }

    /**
     * 带字段校验的调用：strictCheck 抛异常（如缺 summary / segmentRef 越界）视为该候选失败，
     * 自动换下一个模型，避免弱模型的一次残缺输出把整场会议记为 FAILED。
     */
    public JsonNode chatJson(String systemPrompt, String userInput,
                             Consumer<JsonNode> strictCheck) {
        DigestLlmClient.ChatResult result = digestLlmClient.chat(systemPrompt, userInput, strictCheck);
        lastUsed = result.providerCode() + "/" + result.model();
        return result.json();
    }
}
