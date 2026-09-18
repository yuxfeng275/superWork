package com.bu.management.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OA 网页通道核心解析：待办行结构（与线上 doProjection 返回一致）→ 统一事项结构。
 */
class SeeyonOaWebChannelTest {

    private static final String ROW_JSON = """
            {"cells":[
              {"id":"6104928668285224556","cellContentHTML":"请假申请-王昆-电商业务BU-2026-09-17 14:12",
               "linkURL":"/collaboration/collaboration.do?method=summary&openFrom=listPending&affairId=6104928668285224556&showTab=true"},
              {"id":"0","cellContentHTML":"王昆","handler":{"click":{"parameter":"5111524798848191695","name":"showMemberCard"}}},
              {"id":"0","cellContentHTML":"今日14:14"},
              {"id":"0","cellContentHTML":"考勤审批"}
            ]}
            """;

    @Test
    void parsesRowIntoWebAffair() throws Exception {
        SeeyonOaWebChannel channel = new SeeyonOaWebChannel(null, null, null, new ObjectMapper());
        JsonNode row = new ObjectMapper().readTree(ROW_JSON);
        List<SeeyonOaWebChannel.WebAffair> affairs = invokeParse(channel, row);
        assertThat(affairs).hasSize(1);
        SeeyonOaWebChannel.WebAffair affair = affairs.get(0);
        assertThat(affair.affairId()).isEqualTo("6104928668285224556");
        assertThat(affair.title()).contains("请假申请-王昆");
        assertThat(affair.sender()).isEqualTo("王昆");
        assertThat(affair.receiveTime()).isEqualTo("今日14:14");
        assertThat(affair.type()).isEqualTo("考勤审批");
        assertThat(affair.linkUrl()).contains("collaboration.do?method=summary");
    }

    @Test
    void logoutDetectionWorks() {
        SeeyonOaWebChannel channel = new SeeyonOaWebChannel(null, null, null, new ObjectMapper());
        assertThat(invokeIsLoggedOut(channel, "\"__LOGOUT\"".getBytes())).isTrue();
        assertThat(invokeIsLoggedOut(channel, "<html class=\"h100b overflow_login\">x</html>".getBytes())).isTrue();
        assertThat(invokeIsLoggedOut(channel, "{\"Total\":\"80\"}".getBytes())).isFalse();
        assertThat(invokeIsLoggedOut(channel, new byte[0])).isFalse();
    }

    @Test
    void normalizesCookieInput() {
        SeeyonOaWebChannel channel = new SeeyonOaWebChannel(null, null, null, new ObjectMapper());
        assertThat(invokeNormalize(channel, "  ABC123  ")).isEqualTo("JSESSIONID=ABC123");
        assertThat(invokeNormalize(channel, "JSESSIONID=ABC123; ts=1")).isEqualTo("JSESSIONID=ABC123; ts=1");
        assertThat(invokeNormalize(channel, " ")).isNull();
        assertThat(invokeNormalize(channel, null)).isNull();
    }

    @Test
    void mapsSeeyonLoginErrorsAndBuildsV9FormBody() {
        SeeyonOaWebChannel channel = new SeeyonOaWebChannel(null, null, null, new ObjectMapper());
        assertThat(invokeHint(channel, "1")).contains("账号或密码错误");
        assertThat(invokeHint(channel, "9")).contains("验证码错误");
        assertThat(invokeHint(channel, "11")).contains("登录参数不完整");
        String body = invokeFormBody(channel, "alice", "secret", "AB12");
        assertThat(body).contains("login_username=alice");
        assertThat(body).contains("login_password=secret");
        assertThat(body).contains("login_validatePwdStrength=1");
        assertThat(body).contains("login.VerifyCode=AB12");
        assertThat(body).doesNotContain("login_password1=");
    }

    private List<SeeyonOaWebChannel.WebAffair> invokeParse(SeeyonOaWebChannel channel, JsonNode row) {
        try {
            var method = SeeyonOaWebChannel.class.getDeclaredMethod("parseRow", JsonNode.class);
            method.setAccessible(true);
            SeeyonOaWebChannel.WebAffair affair = (SeeyonOaWebChannel.WebAffair) method.invoke(channel, row);
            return affair == null ? List.of() : List.of(affair);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean invokeIsLoggedOut(SeeyonOaWebChannel channel, byte[] body) {
        try {
            var method = SeeyonOaWebChannel.class.getDeclaredMethod("isLoggedOut", byte[].class);
            method.setAccessible(true);
            return (boolean) method.invoke(channel, (Object) body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String invokeNormalize(SeeyonOaWebChannel channel, String raw) {
        try {
            var method = SeeyonOaWebChannel.class.getDeclaredMethod("normalizeCookie", String.class);
            method.setAccessible(true);
            return (String) method.invoke(channel, raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String invokeHint(SeeyonOaWebChannel channel, String code) {
        try {
            var method = SeeyonOaWebChannel.class.getDeclaredMethod("loginErrorHint", String.class);
            method.setAccessible(true);
            return (String) method.invoke(channel, code);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String invokeFormBody(SeeyonOaWebChannel channel, String username, String password, String captcha) {
        try {
            var method = SeeyonOaWebChannel.class.getDeclaredMethod(
                    "loginFormBody", String.class, String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(channel, username, password, captcha);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
