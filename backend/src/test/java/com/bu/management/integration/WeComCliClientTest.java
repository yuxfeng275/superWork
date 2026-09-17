package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * wecom-cli 客户端：进程执行、输出解析与「品类未授权」错误分类。
 * 用假 CLI 脚本替身验证，不依赖真实企业微信与二进制。
 */
class WeComCliClientTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WeComCliClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new WeComCliClient(null, objectMapper);
        ReflectionTestUtils.setField(client, "cliBin", tempDir.resolve("fake-cli").toString());
        ReflectionTestUtils.setField(client, "configDirOverride", tempDir.resolve("config").toString());
    }

    private void fakeCli(String script) throws Exception {
        Path bin = tempDir.resolve("fake-cli");
        Files.writeString(bin, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    @DisplayName("成功响应：解析 JSON 载荷，errcode=0")
    void parsesSuccessfulPayload() throws Exception {
        fakeCli("printf '%s' '{\"errcode\":0,\"errmsg\":\"ok\",\"items\":[{\"title\":\"周报\"}]}'");

        WeComCliClient.CliResult result = client.exec("todo", List.of("list"));

        assertThat(result.success()).isTrue();
        assertThat(result.authFailure()).isFalse();
        JsonNode payload = result.payload();
        assertThat(payload.path("items").path(0).path("title").asText()).isEqualTo("周报");
    }

    @Test
    @DisplayName("品类授权过期（850003）：标记 authFailure 并原样保留续期引导")
    void classifiesExpiredAuthorization() throws Exception {
        fakeCli("printf '%s' '{\"errcode\":850003,\"errmsg\":\"authorization expired\",\"help_message\":\"当前机器人「待办」使用权限已过期\\\\n请[点击这里](https://work.weixin.qq.com/ai/aiHelper/authorizationList?type=5)\"}'");

        WeComCliClient.CliResult result = client.exec("todo", List.of("list"));

        assertThat(result.success()).isFalse();
        assertThat(result.authFailure()).isTrue();
        assertThat(result.errcode()).isEqualTo(850003);
        // 官方要求 help_message 逐字展示，仅还原字面换行
        assertThat(client.describe(result))
                .contains("当前机器人「待办」使用权限已过期")
                .contains("https://work.weixin.qq.com/ai/aiHelper/authorizationList?type=5")
                .doesNotContain("\\n");
    }

    @Test
    @DisplayName("未授权（850002）与部分未授权（851008）同样归类为授权问题")
    void classifiesUnauthorizedCodes() throws Exception {
        fakeCli("printf '%s' '{\"errcode\":850002,\"errmsg\":\"no authorization\"}'");
        assertThat(client.exec("meeting", List.of("list")).authFailure()).isTrue();

        fakeCli("printf '%s' '{\"errcode\":851008,\"errmsg\":\"partial no authorization\"}'");
        assertThat(client.exec("doc", List.of("search")).authFailure()).isTrue();
    }

    @Test
    @DisplayName("业务错误（非授权类）：不标记 authFailure，描述带 errcode")
    void keepsBusinessErrorsDistinct() throws Exception {
        fakeCli("printf '%s' '{\"errcode\":40001,\"errmsg\":\"invalid param\"}'");

        WeComCliClient.CliResult result = client.exec("todo", List.of("create"));

        assertThat(result.authFailure()).isFalse();
        assertThat(client.describe(result)).contains("40001").contains("invalid param");
    }

    @Test
    @DisplayName("CLI 自身错误信封：解析 error.code/message")
    void parsesCliErrorEnvelope() throws Exception {
        fakeCli("printf '%s' '{\"error\":{\"type\":\"UsageError\",\"code\":893201,\"message\":\"unknown argument\"}}'; exit 2");

        WeComCliClient.CliResult result = client.exec("todo", List.of("nope"));

        assertThat(result.success()).isFalse();
        assertThat(result.errcode()).isEqualTo(893201);
        assertThat(result.errmsg()).isEqualTo("unknown argument");
    }

    @Test
    @DisplayName("授权状态：读取 Bot ID 与 authorized")
    void readsAuthorizationStatus() throws Exception {
        fakeCli("printf '%s\\n' 'Status: authorized' 'Bot ID: aibTESTBOT'");

        WeComCliClient.CliStatus status = client.status();

        assertThat(status.authorized()).isTrue();
        assertThat(status.botId()).isEqualTo("aibTESTBOT");
    }

    @Test
    @DisplayName("未授权状态：给出可操作提示")
    void reportsUnauthorizedStatus() throws Exception {
        fakeCli("printf '%s\\n' 'Status: unauthorized'");

        WeComCliClient.CliStatus status = client.status();

        assertThat(status.authorized()).isFalse();
        assertThat(status.hint()).contains("未授权");
    }

    @Test
    @DisplayName("二进制缺失：状态与执行都给出明确错误，不抛底层异常")
    void reportsMissingBinary() throws Exception {
        ReflectionTestUtils.setField(client, "cliBin", tempDir.resolve("not-there").toString());

        assertThat(client.status().hint()).contains("wecom-cli 未安装");
        assertThatThrownBy(() -> client.exec("todo", List.of("list")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wecom-cli 未安装");
    }

    @Test
    @DisplayName("授权执行：把 Bot 凭证透传给 CLI 并在成功后回报状态")
    void authorizesWithBotCredentials() throws Exception {
        fakeCli("case \"$*\" in *\"auth init\"*) echo init-ok;; *\"auth show\"*) printf '%s\\n' 'Status: authorized' 'Bot ID: aibBOT';; esac");

        WeComCliClient.CliStatus status = client.authorizeWithBotCredentials("aibBOT", "s3cret");

        assertThat(status.authorized()).isTrue();
        assertThat(status.botId()).isEqualTo("aibBOT");
    }

    @Test
    @DisplayName("授权失败：抛出含 CLI 原始输出的异常")
    void surfacesAuthorizationFailure() throws Exception {
        fakeCli("echo '{\"error\":{\"code\":893201,\"message\":\"invalid bot secret\"}}'; exit 1");

        assertThatThrownBy(() -> client.authorizeWithBotCredentials("bot", "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("机器人凭证校验失败");
    }

    @Test
    @DisplayName("连接器未配置：botId/botSecret 为空时授权被拒绝")
    void requiresStoredCredentials() {
        WeComCliClient.CliStatus status = client.status();
        assertThat(status.authorized()).isFalse();
    }

    @Test
    @DisplayName("缺少 Bot 凭证参数：直接报参数错误")
    void validatesBotCredentialInput() {
        assertThatThrownBy(() -> client.authorizeWithBotCredentials("", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bot ID 与 Bot Secret 均不能为空");
    }

    @Test
    @DisplayName("连接器读取：未找到企业微信连接器时抛出可读异常")
    void requiresConnectorEntity() {
        ConnectorRegistryService registry = org.mockito.Mockito.mock(ConnectorRegistryService.class);
        org.mockito.Mockito.when(registry.findByCode(ConnectorRegistryService.CODE_WECOM))
                .thenReturn(java.util.Optional.empty());
        WeComCliClient isolated = new WeComCliClient(registry, objectMapper);

        assertThatThrownBy(isolated::connectorEntity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未找到企业微信连接器");
    }
}
