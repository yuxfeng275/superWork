package com.bu.management.controller;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.Connector;
import com.bu.management.service.ConnectorRegistryService;
import com.bu.management.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企微智能机器人（aibot）凭据下发端点。
 *
 * <p><b>仅供内网 ai-sidecar 长连接使用</b>：ai-sidecar 用 botId/secret 拉起企微智能机器人回调，
 * 收到的回复再经 {@code /internal/opportunity/reply}（按 senderKey 反查问句）回到本服务。
 * 凭据只在内部链路上流转，不返回前端、不进入 AI 工具输出。
 *
 * <p>鉴权口径与 {@link OpportunityReplyInternalController#tokenOk} <b>完全一致</b>：SecurityConfig 放行
 * {@code /internal/wecom/**}，靠 {@code X-Sidecar-Token} 常量时间比对防护；令牌未配置（开发环境）直接放行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeComAibotInternalController {

    /** 凭据响应体（botId + secret，secret 为解密后的明文）。 */
    public record AibotCredentials(String botId, String secret) {
    }

    private final ConnectorRegistryService connectorRegistryService;
    private final ProjectContextProperties properties;

    /** 取企微智能机器人凭据；未配置 Bot ID/Secret 时返回 404，让 sidecar 明确「没得连」而不是空转。 */
    @GetMapping("/internal/wecom/aibot-credentials")
    public ResponseEntity<?> credentials(HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        // 每次调用记一条来源 IP，便于确认只有内网 sidecar 在拉凭据
        log.info("企微智能机器人凭据下发: ip={}", clientIp(request));
        Optional<Connector> connector = connectorRegistryService.findByCode(ConnectorRegistryService.CODE_WECOM);
        if (connector.isEmpty()) {
            return ResponseEntity.status(404).body(Result.error(404, "智能机器人未配置 Bot ID/Secret"));
        }
        String botId = connectorRegistryService.extra(connector.get(), "botId");
        String secret = connectorRegistryService.credential(connector.get(), "botSecret");
        if (!StringUtils.hasText(botId) || !StringUtils.hasText(secret)) {
            return ResponseEntity.status(404).body(Result.error(404, "智能机器人未配置 Bot ID/Secret"));
        }
        return ResponseEntity.ok(Result.success(new AibotCredentials(botId, secret)));
    }

    /** 未配置令牌（开发环境）直接放行；配置后做常量时间比对。 */
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

    /** 调用方来源 IP（内网直连，取 remoteAddr 即可）。 */
    private static String clientIp(HttpServletRequest request) {
        return request == null ? "" : request.getRemoteAddr();
    }
}
