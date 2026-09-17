package com.bu.management.service;

import com.bu.management.config.EmailProperties;
import com.bu.management.entity.Connector;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.integration.WorktimeApiClient;
import com.bu.management.integration.YunxiaoClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 连接器连接测试分发：内置系统按其语义探活（云效取当前用户、工时取可见业务线、
 * DeepSeek/企微走各自客户端、GLM 走最小对话请求、邮箱统计已绑定账号），
 * 其余通用连接器交给 {@link ConnectorRegistryService#test(Long)} 按认证类型探活。
 * 结果统一写回连接器的 last_test_* 字段。
 *
 * @author BU Team
 * @since 2026-09-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorTestDispatcher {

    private final ConnectorRegistryService registryService;
    private final YunxiaoClient yunxiaoClient;
    private final WorktimeApiClient worktimeApiClient;
    private final DeepSeekDigestClient deepSeekClient;
    private final WeComClient weComClient;
    private final EmailAccountMapper emailAccountMapper;
    private final EmailProperties emailProperties;

    public ConnectorRegistryService.ConnectorView test(Long id) {
        Connector connector = registryService.entity(id);
        String code = connector.getCode() == null ? "" : connector.getCode();
        if (PROBE_HANDLED.contains(code)) {
            String message;
            boolean success;
            try {
                message = probe(connector);
                success = true;
            } catch (RuntimeException e) {
                success = false;
                message = e.getMessage() == null ? "连接失败" : e.getMessage();
                log.info("连接器测试失败: code={}, message={}", code, message);
            }
            registryService.recordTestResult(id, success, message);
            if (!success) {
                throw new IllegalStateException(message);
            }
            return registryService.get(id);
        }
        return registryService.test(id);
    }

    private static final List<String> PROBE_HANDLED = List.of(
            ConnectorRegistryService.CODE_YUNXIAO, ConnectorRegistryService.CODE_WORKTIME,
            ConnectorRegistryService.CODE_DEEPSEEK, ConnectorRegistryService.CODE_GLM,
            ConnectorRegistryService.CODE_WECOM, ConnectorRegistryService.CODE_MAIL);

    private String probe(Connector connector) {
        return switch (connector.getCode()) {
            case ConnectorRegistryService.CODE_YUNXIAO -> probeYunxiao();
            case ConnectorRegistryService.CODE_WORKTIME -> probeWorktime();
            case ConnectorRegistryService.CODE_DEEPSEEK -> probeDeepSeek();
            case ConnectorRegistryService.CODE_GLM -> probeGlm(connector);
            case ConnectorRegistryService.CODE_WECOM -> probeWeCom();
            case ConnectorRegistryService.CODE_MAIL -> probeMail();
            default -> throw new IllegalStateException("不支持的连接器：" + connector.getCode());
        };
    }

    /** 云效：读取当前令牌对应的用户，顺带验证组织可见性。 */
    private String probeYunxiao() {
        JsonNode response = yunxiaoClient.getCurrentUser();
        if (response == null) {
            throw new IllegalStateException("云效返回为空，请检查服务地址与令牌");
        }
        JsonNode user = response.has("data") && response.path("data").isObject() ? response.path("data") : response;
        String name = user.path("name").asText(user.path("id").asText(""));
        return StringUtils.hasText(name) ? "连接成功：" + name : "连接成功";
    }

    /** 工时系统：登录并返回账号可见业务线范围。 */
    private String probeWorktime() {
        JsonNode filters = worktimeApiClient.testConnection();
        JsonNode lines = filters == null ? null : filters.path("business_lines");
        int count = lines != null && lines.isArray() ? lines.size() : 0;
        String cutoff = filters == null ? null : filters.path("data_cutoff_date").asText(null);
        StringBuilder message = new StringBuilder("连接成功");
        if (count > 0) message.append("，可见业务线 ").append(count).append(" 条");
        if (StringUtils.hasText(cutoff)) message.append("，数据截止 ").append(cutoff);
        return message.toString();
    }

    /** DeepSeek：GET /models 探活（与邮件摘要同一条通路）。 */
    private String probeDeepSeek() {
        deepSeekClient.testConnection();
        return "连接成功";
    }

    /** 智谱 GLM：最小对话请求探活（OpenAI 兼容 /chat/completions）。 */
    private String probeGlm(Connector connector) {
        String token = registryService.credential(connector, "token");
        String model = registryService.extra(connector, "model", "glm-5.3");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));
        body.put("max_tokens", 1);
        registryService.postJson(connector, "/chat/completions", body, token);
        return "连接成功（模型 " + model + "）";
    }

    /** 企业微信：gettoken 探活。 */
    private String probeWeCom() {
        weComClient.testConnection();
        return "连接成功";
    }

    /**
     * 邮件：邮箱账号按用户绑定，这里检查邮件模块可用性并统计已启用账号。
     * 未绑定账号视为未就绪（不抛异常，返回可操作提示）。
     */
    private String probeMail() {
        if (!emailProperties.isCredentialEncryptionConfigured()) {
            throw new IllegalStateException("邮件凭据加密密钥未配置，请在服务端配置 EMAIL_CREDENTIAL_ENCRYPTION_KEY");
        }
        int accounts = emailAccountMapper.selectEnabledAccounts().size();
        if (accounts == 0) {
            throw new IllegalStateException("邮件模块可用，但尚无已绑定的邮箱账号；请在「邮箱账号」中为成员绑定邮箱");
        }
        return "邮件模块可用，已启用邮箱账号 " + accounts + " 个";
    }
}
