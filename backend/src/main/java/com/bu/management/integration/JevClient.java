package com.bu.management.integration;

import com.bu.management.entity.Connector;
import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TypeSafe Jev（System One 决策模型）客户端：state + Noul 问题 → 概率化是/否。
 * 用于会议纠偏的守门判定。typesafe 连接器未启用/未配凭据/调用失败 → 一律返回 empty（调用方降级为无门控）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevClient {

    public static final String CONNECTOR_CODE = "typesafe";
    private static final String MODEL = "jev-latest";
    private static final String QUESTION_ID = "judge";
    private static final double ACCEPT_THRESHOLD = 0.5;

    private final ConnectorRegistryService registryService;

    /** Jev 是否可用：typesafe 连接器 READY（启用 + 凭据已配）。 */
    public boolean available() {
        return registryService.findByCode(CONNECTOR_CODE)
                .filter(c -> "READY".equals(registryService.status(c)))
                .filter(c -> StringUtils.hasText(registryService.credential(c, "token")))
                .isPresent();
    }

    /**
     * Noul 判定：state + instructions → true/false；不可用或调用失败 → empty（降级为无门控）。
     */
    public Optional<Boolean> judge(String state, String instructions) {
        Optional<Connector> connector = registryService.findByCode(CONNECTOR_CODE)
                .filter(c -> "READY".equals(registryService.status(c)));
        if (connector.isEmpty()) {
            return Optional.empty();
        }
        Connector entity = connector.get();
        String token = registryService.credential(entity, "token");
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("type", "noul");
            question.put("instructions", instructions);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("state", state);
            body.put("model", MODEL);
            body.put("questions", Map.of(QUESTION_ID, question));
            JsonNode answer = registryService.postJson(entity, "/v1/systemone", body, token)
                    .path("answers").path(QUESTION_ID);
            if (!"noul".equals(answer.path("type").asText()) || !answer.path("noul").isNumber()) {
                log.warn("Jev 响应缺少 noul 答案：{}", answer);
                return Optional.empty();
            }
            return Optional.of(answer.path("noul").asDouble() >= ACCEPT_THRESHOLD);
        } catch (RuntimeException e) {
            log.warn("Jev 判定失败（降级为无门控）：{}", e.getMessage());
            return Optional.empty();
        }
    }
}
