package com.bu.management.controller;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.service.OpportunityFollowUpService;
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
 * 商机跟进问句的企微回复入站端点。
 *
 * <p>转发器/机器人把负责人的回复丢进来，本端点只做两件事：
 * <ol>
 *   <li>找出该商机 {@code 24 小时}内最新一条 {@code SENT} 问句（没有就忽略，不新建、不猜）；</li>
 *   <li>把回复原文与解析结果写回问句台账，等人工在站内确认才落跟进记录。</li>
 * </ol>
 *
 * <p>鉴权口径与 {@link WeComInboundController#tokenOk} <b>完全一致</b>：SecurityConfig 放行该前缀，
 * 靠 {@code X-Sidecar-Token} 常量时间比对防护；令牌未配置（开发环境）直接放行。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpportunityReplyInternalController {

    /** 入站请求体。 */
    public record OpportunityReplyRequest(String senderKey, Long opportunityId, String text,
                                         String msgId, String channel, Boolean external) {
    }

    private final OpportunityFollowUpService followUpService;
    private final ProjectContextProperties properties;

    /** 企微回复入站。 */
    @PostMapping("/internal/opportunity/reply")
    public ResponseEntity<?> reply(@RequestBody OpportunityReplyRequest body, HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "请求体不能为空"));
        }
        if (Boolean.TRUE.equals(body.external())) {
            return ResponseEntity.badRequest().body(Result.error(400,
                    "外部联系人单聊不走本端点：需企业管理员开通会话内容存档（msgaudit）授权"));
        }
        if (body.opportunityId() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "opportunityId 不能为空"));
        }
        String text = strip(body.text());
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "text 不能为空"));
        }
        OpportunityFollowupPrompt prompt = followUpService.applyReply(
                strip(body.senderKey()), body.opportunityId(), text, strip(body.msgId), strip(body.channel()));
        if (prompt == null) {
            // 没有 24 小时内的待回问句：明确告知已忽略，重复投递同样落到这里
            return ResponseEntity.ok(Result.success("没有待回复的问句，已忽略", null));
        }
        return ResponseEntity.ok(Result.success(prompt));
    }

    private static String strip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
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
}
