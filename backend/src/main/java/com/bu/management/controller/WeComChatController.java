package com.bu.management.controller;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.service.WeComChatService;
import com.bu.management.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企微自由对话内部端点：sidecar 商机问句未匹配时调用。
 *
 * <p>鉴权与 /internal/opportunity/reply 一致：SecurityConfig 放行前缀，
 * 靠 X-Sidecar-Token 常量时间比对；令牌未配置时按开发口径放行。
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeComChatController {

    /** 请求体：企微发送人 + 文本。 */
    public record ChatRequest(String senderKey, String text) {
    }

    private final WeComChatService chatService;
    private final ProjectContextProperties properties;

    @PostMapping("/internal/ai-agent/wecom-chat")
    public ResponseEntity<?> chat(@RequestBody(required = false) ChatRequest body,
                                  HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        if (body == null || !StringUtils.hasText(body.senderKey())
                || !StringUtils.hasText(body.text())) {
            return ResponseEntity.badRequest().body(Result.error(400, "senderKey 与 text 必填"));
        }
        return ResponseEntity.ok(Result.success(chatService.reply(body.senderKey(), body.text())));
    }

    private boolean tokenOk(HttpServletRequest request) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String actual = request.getHeader("X-Sidecar-Token");
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
