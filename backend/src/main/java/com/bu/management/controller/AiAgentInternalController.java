package com.bu.management.controller;

import com.bu.management.config.AiAgentProperties;
import com.bu.management.service.AiAgentToolService;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bu.management.vo.Result;

/**
 * 侧车工具回调端点：POST /internal/ai-agent/tools。
 * 由 SecurityConfig permitAll 放行，靠 X-Sidecar-Token 常量时间比对防护。
 * 任何情况下都返回 200 {"content","isError"}，绝不向侧车抛异常。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AiAgentInternalController {

    private final AiAgentToolService toolService;
    private final AiAgentProperties properties;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/internal/ai-agent/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> execute(@RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        AiAgentToolResult denied = checkToken(request);
        if (denied != null) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        try {
            String runId = text(body, "runId");
            String toolName = text(body, "toolName");
            String args = body != null && body.has("args") && body.get("args").isObject()
                    ? body.get("args").toString() : "{}";
            if (!StringUtils.hasText(toolName)) {
                return ResponseEntity.ok(new AiAgentToolResult("缺少 toolName", true));
            }
            return ResponseEntity.ok(toolService.execute(runId, toolName, args));
        } catch (Exception e) {
            log.warn("AI 工具回调处理失败: {}", e.getMessage());
            return ResponseEntity.ok(new AiAgentToolResult("工具执行失败：" + e.getMessage(), true));
        }
    }

    /**
     * 配置了令牌时做常量时间比对；未配置（开发环境）直接放行。
     */
    private AiAgentToolResult checkToken(HttpServletRequest request) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected)) {
            return null;
        }
        String provided = request.getHeader("X-Sidecar-Token");
        if (provided == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return new AiAgentToolResult("invalid token", true);
        }
        return null;
    }

    private String text(JsonNode body, String field) {
        return body != null && body.has(field) && body.get(field).isTextual()
                ? body.get(field).asText() : null;
    }
}
