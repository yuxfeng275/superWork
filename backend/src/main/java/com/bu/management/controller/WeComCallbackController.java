package com.bu.management.controller;

import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.integration.WeComCallbackCrypto;
import com.bu.management.service.OpportunityFollowUpService;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业微信 HTTP 回调（与长连接机器人并存的第二条入站通道）。
 *
 * <p>用途：长连接不可用/未开通时，企微把消息推到这个公网地址。协议要求：
 * <ul>
 *   <li>GET 验证 URL：验签后解密 {@code echostr} 并原样返回；</li>
 *   <li>POST 收消息：验签 → 解密 XML → 取发送人/正文 → 写商机回复台账 → 被动回复一句；</li>
 *   <li>未配置 token/aes-key/corp-id 时返回 503，明确「未启用」，不假装成功。</li>
 * </ul>
 *
 * <p>不记录正文与密文；只记 msgId 与处理结果。被动回复失败时仍回 {@code success}，
 * 避免企微因 5xx 持续重试把同一条消息写多次（去重仍在 service 的 msgId 上）。
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeComCallbackController {

    private static final String NOT_CONFIGURED = "WECOM_CALLBACK_NOT_CONFIGURED";

    private final WeComCallbackCrypto crypto;

    private final OpportunityFollowUpService followUpService;

    /** URL 验证：企微要求原样返回解密后的 echostr（纯文本）。 */
    @GetMapping("/api/wecom/callback")
    public ResponseEntity<String> verify(@RequestParam("msg_signature") String signature,
                                         @RequestParam("timestamp") String timestamp,
                                         @RequestParam("nonce") String nonce,
                                         @RequestParam("echostr") String echostr) {
        if (!crypto.configured()) {
            log.warn("企微回调未配置（token/aes-key/corp-id），拒绝 URL 验证");
            return ResponseEntity.status(503).body(NOT_CONFIGURED);
        }
        if (!crypto.verify(signature, timestamp, nonce, echostr)) {
            log.warn("企微回调 URL 验证签名不匹配");
            return ResponseEntity.status(403).body("invalid signature");
        }
        try {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(crypto.decrypt(echostr));
        } catch (IllegalArgumentException e) {
            log.warn("企微回调 URL 验证解密失败：{}", e.getMessage());
            return ResponseEntity.badRequest().body("invalid echostr");
        }
    }

    /** 消息回调：验签→解密→处理→被动回复（文本）。 */
    @PostMapping("/api/wecom/callback")
    public ResponseEntity<String> receive(@RequestParam("msg_signature") String signature,
                                          @RequestParam("timestamp") String timestamp,
                                          @RequestParam("nonce") String nonce,
                                          @RequestBody(required = false) String body) {
        if (!crypto.configured()) {
            return ResponseEntity.status(503).body(NOT_CONFIGURED);
        }
        if (!StringUtils.hasText(body)) {
            return ResponseEntity.badRequest().body("empty body");
        }
        String encrypted = tag(body, "Encrypt");
        if (!StringUtils.hasText(encrypted)) {
            return ResponseEntity.badRequest().body("missing Encrypt");
        }
        if (!crypto.verify(signature, timestamp, nonce, encrypted)) {
            log.warn("企微回调消息签名不匹配");
            return ResponseEntity.status(403).body("invalid signature");
        }
        String plain;
        try {
            plain = crypto.decrypt(encrypted);
        } catch (IllegalArgumentException e) {
            log.warn("企微回调消息解密失败：{}", e.getMessage());
            return ResponseEntity.badRequest().body("invalid encrypt");
        }
        String msgType = tag(plain, "MsgType");
        String content = tag(plain, "Content");
        String from = tag(plain, "FromUserName");
        String msgId = tag(plain, "MsgId");
        if (!"text".equalsIgnoreCase(msgType) || !StringUtils.hasText(content)) {
            return ResponseEntity.ok("success");
        }
        String replyText;
        try {
            OpportunityFollowupPrompt prompt = followUpService.applyReplyBySender(
                    from, content, msgId, null, null, "wecom_callback");
            replyText = prompt == null
                    ? "已收到（没有待回复的问句，可在合拍里手工补录）"
                    : "已收到并写入合拍";
            log.info("企微回调文本已处理：from={} msgId={} matched={}", from, msgId, prompt != null);
        } catch (Exception e) {
            // 处理失败不回 5xx：企微会重试；这里只记日志，用户稍后可在站内补录
            log.warn("企微回调文本处理失败：from={} msgId={} err={}", from, msgId, e.getMessage());
            return ResponseEntity.ok("success");
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(passiveReply(from, replyText));
        } catch (Exception e) {
            log.warn("企微回调被动回复失败（已 ACK，避免重试）：{}", e.getMessage());
            return ResponseEntity.ok("success");
        }
    }

    /** 构造并加密被动回复 XML；签名用新的 timestamp/nonce。 */
    private String passiveReply(String toUser, String content) {
        String replyXml = "<xml>"
                + "<ToUserName><![CDATA[" + toUser + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + crypto.corpId() + "]]></FromUserName>"
                + "<CreateTime>" + Instant.now().getEpochSecond() + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + content + "]]></Content>"
                + "</xml>";
        String random16 = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String encrypt = crypto.encrypt(replyXml, random16);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String signature = crypto.sign(ts, nonce, encrypt);
        return "<xml>"
                + "<Encrypt><![CDATA[" + encrypt + "]]></Encrypt>"
                + "<MsgSignature><![CDATA[" + signature + "]]></MsgSignature>"
                + "<TimeStamp>" + ts + "</TimeStamp>"
                + "<Nonce><![CDATA[" + nonce + "]]></Nonce>"
                + "</xml>";
    }

    private static String tag(String xml, String name) {
        Matcher cdata = Pattern.compile("<" + name + "><!\\[CDATA\\[(.*?)\\]\\]></" + name + ">",
                Pattern.DOTALL).matcher(xml);
        if (cdata.find()) {
            return cdata.group(1);
        }
        Matcher plain = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return plain.find() ? plain.group(1).strip() : "";
    }
}
