package com.bu.management.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bu.management.integration.WeComCallbackCrypto;
import com.bu.management.service.OpportunityFollowUpService;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 企微 HTTP 回调的三条主路径：URL 验证、消息入站、未配置降级；外加签名不匹配拒绝。
 */
@ExtendWith(MockitoExtension.class)
class WeComCallbackControllerTest {

    private static final String CORP_ID = "ww-test-corp";
    private static final String TOKEN = "callback-token";
    private static final String AES_KEY = randomKey();
    private static final String RANDOM16 = "0123456789abcdef";

    @Mock
    private OpportunityFollowUpService followUpService;

    private WeComCallbackCrypto crypto;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        crypto = buildCrypto();
        mvc = MockMvcBuilders.standaloneSetup(new WeComCallbackController(crypto, followUpService)).build();
    }

    @Test
    @DisplayName("GET 验证：验签后原样返回解密 echostr")
    void verifiesUrl() throws Exception {
        String echostr = crypto.encrypt("echo-ok", RANDOM16);
        String signature = crypto.sign("1730000000", "nonce-1", echostr);

        mvc.perform(get("/api/wecom/callback")
                        .param("msg_signature", signature)
                        .param("timestamp", "1730000000")
                        .param("nonce", "nonce-1")
                        .param("echostr", echostr))
                .andExpect(status().isOk())
                .andExpect(content().string("echo-ok"));
    }

    @Test
    @DisplayName("未配置：503 明确降级，不假装成功")
    void notConfiguredIs503() throws Exception {
        MockMvc bare = MockMvcBuilders.standaloneSetup(
                new WeComCallbackController(new WeComCallbackCrypto(), followUpService)).build();

        bare.perform(get("/api/wecom/callback")
                        .param("msg_signature", "x")
                        .param("timestamp", "1")
                        .param("nonce", "n")
                        .param("echostr", "e"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("WECOM_CALLBACK_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("POST 文本：验签解密→写台账→加密被动回复")
    void receivesTextAndReplies() throws Exception {
        when(followUpService.applyReplyBySender(eq("wx-user-1"), eq("客户下周给答复"),
                eq("msg-1"), any(), any(), eq("wecom_callback"))).thenReturn(null);
        String plain = "<xml><ToUserName><![CDATA[ww-test-corp]]></ToUserName>"
                + "<FromUserName><![CDATA[wx-user-1]]></FromUserName>"
                + "<CreateTime>1730000000</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[客户下周给答复]]></Content>"
                + "<MsgId>msg-1</MsgId></xml>";
        String encrypted = crypto.encrypt(plain, RANDOM16);
        String signature = crypto.sign("1730000000", "nonce-1", encrypted);
        String body = "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";

        String response = mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", signature)
                        .param("timestamp", "1730000000")
                        .param("nonce", "nonce-1")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(followUpService).applyReplyBySender("wx-user-1", "客户下周给答复",
                "msg-1", null, null, "wecom_callback");
        Matcher matcher = Pattern.compile("<Encrypt><!\\[CDATA\\[(.*?)\\]\\]></Encrypt>",
                Pattern.DOTALL).matcher(response);
        assertThat(matcher.find()).isTrue();
        String replyPlain = crypto.decrypt(matcher.group(1));
        assertThat(replyPlain).contains("已收到").contains("没有待回复的问句");
    }

    @Test
    @DisplayName("签名不匹配：403，不进入解密与业务处理")
    void rejectsBadSignature() throws Exception {
        String body = "<xml><Encrypt><![CDATA[abc]]></Encrypt></xml>";
        mvc.perform(post("/api/wecom/callback")
                        .param("msg_signature", "bad")
                        .param("timestamp", "1730000000")
                        .param("nonce", "nonce-1")
                        .contentType(MediaType.APPLICATION_XML)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private static WeComCallbackCrypto buildCrypto() {
        WeComCallbackCrypto instance = new WeComCallbackCrypto();
        ReflectionTestUtils.setField(instance, "token", TOKEN);
        ReflectionTestUtils.setField(instance, "aesKey", AES_KEY);
        ReflectionTestUtils.setField(instance, "corpId", CORP_ID);
        return instance;
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
}
