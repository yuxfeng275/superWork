package com.bu.management.integration;

import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TypeSafe System One（Jev）客户端：state + typed questions → answers。
 * 官方无 Java SDK，走模型自己的地址 + Bearer，不依赖连接器实体。
 */
@Component
@RequiredArgsConstructor
public class JevClient {

    private final ConnectorRegistryService registryService;
    private final ObjectMapper objectMapper;

    public record Evaluation(String model, JsonNode answers) {
        public String choice(String id) {
            return answers.path(id).path("choice").asText("");
        }

        public double confidence(String id) {
            return answers.path(id).path("confidence").asDouble(0);
        }

        public double noul(String id) {
            return answers.path(id).path("noul").asDouble(0);
        }

        public double score(String id) {
            return answers.path(id).path("score").asDouble(0);
        }
    }

    public Evaluation evaluate(String baseUrl, String apiKey, String model,
            Object state, Map<String, Object> questions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("model", StringUtils.hasText(model) ? model : "jev-latest");
        body.put("questions", questions == null ? Map.of() : questions);
        JsonNode response = registryService.postJson(baseUrl, "/v1/systemone", body, apiKey, "TypeSafe Jev");
        JsonNode answers = response == null ? objectMapper.createObjectNode() : response.path("answers");
        if (!answers.isObject() || answers.isEmpty()) {
            throw new IllegalStateException("TypeSafe 返回缺少 answers");
        }
        String resolved = response.path("model").asText(String.valueOf(body.get("model")));
        return new Evaluation(resolved, answers);
    }
}
