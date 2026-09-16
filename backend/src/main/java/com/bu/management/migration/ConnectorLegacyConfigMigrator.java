package com.bu.management.migration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.SeeyonOaTokenCipher;
import com.bu.management.config.WorktimeTokenCipher;
import com.bu.management.config.YunxiaoTokenCipher;
import com.bu.management.entity.Connector;
import com.bu.management.entity.SeeyonOaIntegrationConfig;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.entity.WorktimeIntegrationConfig;
import com.bu.management.entity.YunxiaoIntegrationConfig;
import com.bu.management.mapper.ConnectorMapper;
import com.bu.management.mapper.SeeyonOaIntegrationConfigMapper;
import com.bu.management.mapper.SystemConfigItemMapper;
import com.bu.management.mapper.WorktimeIntegrationConfigMapper;
import com.bu.management.mapper.YunxiaoIntegrationConfigMapper;
import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 历史连接配置一次性搬迁：把散落在旧表（云效/工时/OA 集成配置表）与旧配置组
 * （ai-connector / email-integration / ai-agent / weekly-report 的语雀参数 / oa-vreport）
 * 里的连接参数搬入连接器注册表，随后隐藏源配置项。
 *
 * <p>幂等：每个连接器一个搬迁标记（system_config_item 组 connector-hub，status=0 隐藏），
 * 标记为 done 后不再执行；只填空值，不覆盖连接器里已有的人工配置。
 * 旧密文由各自 cipher 解密后用 EmailCredentialCipher 重新加密（Flyway 无法在 SQL 层解密）。
 *
 * @author BU Team
 * @since 2026-09-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorLegacyConfigMigrator implements ApplicationRunner {

    private static final String MARKER_GROUP = "connector-hub";
    private static final String LEGACY_AI_CONNECTOR_GROUP = "ai-connector";
    private static final String EMAIL_INTEGRATION_GROUP = "email-integration";
    private static final String AI_AGENT_GROUP = "ai-agent";
    private static final String WEEKLY_REPORT_GROUP = "weekly-report";
    private static final String OA_VREPORT_GROUP = "oa-vreport";
    private static final Long SINGLETON_ID = 1L;

    private final ConnectorMapper connectorMapper;
    private final SystemConfigItemMapper configMapper;
    private final EmailCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final YunxiaoTokenCipher yunxiaoCipher;
    private final WorktimeTokenCipher worktimeCipher;
    private final SeeyonOaTokenCipher seeyonCipher;
    private final YunxiaoIntegrationConfigMapper yunxiaoConfigMapper;
    private final WorktimeIntegrationConfigMapper worktimeConfigMapper;
    private final SeeyonOaIntegrationConfigMapper seeyonConfigMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate(ConnectorRegistryService.CODE_YUNXIAO, this::migrateYunxiao);
            migrate(ConnectorRegistryService.CODE_WORKTIME, this::migrateWorktime);
            migrate(ConnectorRegistryService.CODE_OA, this::migrateOa);
            migrate(ConnectorRegistryService.CODE_YUQUE, this::migrateYuque);
            migrate(ConnectorRegistryService.CODE_MAIL, this::migrateMail);
            migrate(ConnectorRegistryService.CODE_DEEPSEEK, this::migrateDeepSeek);
            migrate(ConnectorRegistryService.CODE_GLM, this::migrateGlm);
            migrate(ConnectorRegistryService.CODE_WECOM, this::migrateWecom);
        } catch (Exception e) {
            // 搬迁失败绝不能阻断应用启动（如本地/测试库尚无迁移表）
            log.warn("连接器历史配置搬迁跳过：{}", e.getMessage());
        }
    }

    // ==================== 各连接器搬迁 ====================

    void migrateYunxiao() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_YUNXIAO);
        YunxiaoIntegrationConfig legacy = yunxiaoConfigMapper.selectById(SINGLETON_ID);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        if (legacy != null) {
            copyText(update::setBaseUrl, connector.getBaseUrl(), legacy.getBaseUrl(),
                    "https://openapi-rdc.aliyuncs.com");
            if (!has(connector.getEncryptedToken()) && has(legacy.getEncryptedToken())) {
                update.setEncryptedToken(cipher.encrypt(yunxiaoCipher.decrypt(legacy.getEncryptedToken())));
            }
            putIfAbsent(extra, "edition", normalizeEdition(legacy.getEdition()), "center");
            putIfAbsent(extra, "organizationId", trim(legacy.getOrganizationId()));
            if (isEnabled(legacy.getEnabled())) update.setEnabled(1);
        }
        applyExtra(update, connector, extra);
        save(update);
    }

    void migrateWorktime() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_WORKTIME);
        WorktimeIntegrationConfig legacy = worktimeConfigMapper.selectById(SINGLETON_ID);
        Connector update = new Connector();
        update.setId(connector.getId());
        String baseUrl = legacy == null ? null : legacy.getBaseUrl();
        if (!has(baseUrl)) baseUrl = item(LEGACY_AI_CONNECTOR_GROUP, "worktime.base-url");
        copyText(update::setBaseUrl, connector.getBaseUrl(), baseUrl, "https://worktime.lucidata.cn");
        if (!has(connector.getEncryptedUsername())) {
            String username = legacy != null && has(legacy.getEncryptedEmployeeNo())
                    ? worktimeCipher.decrypt(legacy.getEncryptedEmployeeNo())
                    : item(LEGACY_AI_CONNECTOR_GROUP, "worktime.username");
            if (has(username)) update.setEncryptedUsername(cipher.encrypt(username));
        }
        if (!has(connector.getEncryptedPassword())) {
            String password = legacy != null && has(legacy.getEncryptedPassword())
                    ? worktimeCipher.decrypt(legacy.getEncryptedPassword())
                    : item(LEGACY_AI_CONNECTOR_GROUP, "worktime.password");
            if (has(password)) update.setEncryptedPassword(cipher.encrypt(password));
        }
        boolean legacyEnabled = (legacy != null && isEnabled(legacy.getEnabled()))
                || "true".equalsIgnoreCase(item(LEGACY_AI_CONNECTOR_GROUP, "worktime.enabled"));
        if (legacyEnabled) update.setEnabled(1);
        save(update);
        hide(LEGACY_AI_CONNECTOR_GROUP, "worktime.enabled");
        hide(LEGACY_AI_CONNECTOR_GROUP, "worktime.base-url");
        hide(LEGACY_AI_CONNECTOR_GROUP, "worktime.username");
        hide(LEGACY_AI_CONNECTOR_GROUP, "worktime.password");
        hide(LEGACY_AI_CONNECTOR_GROUP, "worktime.timeout-seconds");
    }

    void migrateOa() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_OA);
        SeeyonOaIntegrationConfig legacy = seeyonConfigMapper.selectById(SINGLETON_ID);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        if (legacy != null) {
            copyText(update::setBaseUrl, connector.getBaseUrl(), legacy.getBaseUrl(), "https://oa.lucidata.cn");
            if (!has(connector.getEncryptedUsername()) && has(legacy.getEncryptedUsername())) {
                update.setEncryptedUsername(cipher.encrypt(seeyonCipher.decrypt(legacy.getEncryptedUsername())));
            }
            if (!has(connector.getEncryptedPassword()) && has(legacy.getEncryptedPassword())) {
                update.setEncryptedPassword(cipher.encrypt(seeyonCipher.decrypt(legacy.getEncryptedPassword())));
            }
            if (!has(connector.getEncryptedToken()) && has(legacy.getEncryptedToken())) {
                update.setEncryptedToken(cipher.encrypt(seeyonCipher.decrypt(legacy.getEncryptedToken())));
            }
            if (isEnabled(legacy.getEnabled())) update.setEnabled(1);
        }
        putIfAbsent(extra, "contractExportUrl", item(OA_VREPORT_GROUP, "sales-contract-export-url"));
        applyExtra(update, connector, extra);
        save(update);
        hideGroup(OA_VREPORT_GROUP);
    }

    void migrateYuque() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_YUQUE);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        copyText(update::setBaseUrl, connector.getBaseUrl(), item(WEEKLY_REPORT_GROUP, "yuque.base-url"),
                "https://lucidata.yuque.com");
        copyText(update::setMcpUrl, connector.getMcpUrl(), item(LEGACY_AI_CONNECTOR_GROUP, "yuque.mcp-url"),
                "https://mcp.yuque.com/mcp");
        if (!has(connector.getEncryptedToken())) {
            String token = item(LEGACY_AI_CONNECTOR_GROUP, "yuque.token");
            if (has(token)) update.setEncryptedToken(cipher.encrypt(token));
        }
        if ("true".equalsIgnoreCase(item(LEGACY_AI_CONNECTOR_GROUP, "yuque.enabled"))) update.setEnabled(1);
        putIfAbsent(extra, "repo", item(WEEKLY_REPORT_GROUP, "yuque.repo"), "vuntcs/cf_records");
        putIfAbsent(extra, "parentDir", item(WEEKLY_REPORT_GROUP, "yuque.parent-dir"));
        applyExtra(update, connector, extra);
        save(update);
        hide(LEGACY_AI_CONNECTOR_GROUP, "yuque.enabled");
        hide(LEGACY_AI_CONNECTOR_GROUP, "yuque.mcp-url");
        hide(LEGACY_AI_CONNECTOR_GROUP, "yuque.token");
        hide(LEGACY_AI_CONNECTOR_GROUP, "yuque.timeout-seconds");
        hide(WEEKLY_REPORT_GROUP, "yuque.repo");
        hide(WEEKLY_REPORT_GROUP, "yuque.parent-dir");
        hide(WEEKLY_REPORT_GROUP, "yuque.base-url");
    }

    void migrateMail() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_MAIL);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        copyText(update::setBaseUrl, connector.getBaseUrl(), item(EMAIL_INTEGRATION_GROUP, "app.public-base-url"));
        String searchDays = item(LEGACY_AI_CONNECTOR_GROUP, "mail.search-days");
        if (has(searchDays)) {
            Object current = extra.get("searchDays");
            if (current == null || matchesAny(String.valueOf(current), "90")) {
                extra.put("searchDays", parseInteger(searchDays, 90));
            }
        }
        applyExtra(update, connector, extra);
        save(update);
        hide(LEGACY_AI_CONNECTOR_GROUP, "mail.search-days");
        hide(EMAIL_INTEGRATION_GROUP, "app.public-base-url");
    }

    void migrateDeepSeek() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_DEEPSEEK);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        String digestBaseUrl = item(EMAIL_INTEGRATION_GROUP, "deepseek.base-url");
        String digestApiKey = item(EMAIL_INTEGRATION_GROUP, "deepseek.api-key");
        String digestModel = item(EMAIL_INTEGRATION_GROUP, "deepseek.model");
        String assistantBaseUrl = item(AI_AGENT_GROUP, "aiagent.deepseek.base-url");
        String assistantApiKey = item(AI_AGENT_GROUP, "aiagent.deepseek.api-key");
        String assistantModel = item(AI_AGENT_GROUP, "aiagent.deepseek.model");
        copyText(update::setBaseUrl, connector.getBaseUrl(), has(digestBaseUrl) ? digestBaseUrl : assistantBaseUrl,
                "https://api.deepseek.com");
        if (!has(connector.getEncryptedToken())) {
            String apiKey = has(digestApiKey) ? digestApiKey : assistantApiKey;
            if (has(apiKey)) update.setEncryptedToken(cipher.encrypt(apiKey));
        }
        boolean digestEnabled = "true".equalsIgnoreCase(item(EMAIL_INTEGRATION_GROUP, "deepseek.enabled"));
        boolean assistantEnabled = "true".equalsIgnoreCase(item(AI_AGENT_GROUP, "aiagent.deepseek.enabled"));
        if (digestEnabled || assistantEnabled) update.setEnabled(1);
        putIfAbsent(extra, "model", has(assistantModel) ? assistantModel : digestModel, "deepseek-v4-flash");
        putIfAbsent(extra, "digestModel", has(digestModel) ? digestModel : assistantModel, "deepseek-chat");
        extra.put("digestEnabled", digestEnabled);
        applyExtra(update, connector, extra);
        save(update);
        hide(EMAIL_INTEGRATION_GROUP, "deepseek.enabled");
        hide(EMAIL_INTEGRATION_GROUP, "deepseek.base-url");
        hide(EMAIL_INTEGRATION_GROUP, "deepseek.model");
        hide(EMAIL_INTEGRATION_GROUP, "deepseek.api-key");
        hide(AI_AGENT_GROUP, "aiagent.deepseek.enabled");
        hide(AI_AGENT_GROUP, "aiagent.deepseek.base-url");
        hide(AI_AGENT_GROUP, "aiagent.deepseek.model");
        hide(AI_AGENT_GROUP, "aiagent.deepseek.api-key");
    }

    void migrateGlm() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_GLM);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        copyText(update::setBaseUrl, connector.getBaseUrl(), item(AI_AGENT_GROUP, "aiagent.base-url"),
                "https://open.bigmodel.cn/api/paas/v4");
        if (!has(connector.getEncryptedToken())) {
            String apiKey = item(AI_AGENT_GROUP, "aiagent.api-key");
            if (has(apiKey)) update.setEncryptedToken(cipher.encrypt(apiKey));
        }
        if ("true".equalsIgnoreCase(item(AI_AGENT_GROUP, "aiagent.enabled"))) update.setEnabled(1);
        putIfAbsent(extra, "model", item(AI_AGENT_GROUP, "aiagent.model"), "glm-5.3");
        applyExtra(update, connector, extra);
        save(update);
        hide(AI_AGENT_GROUP, "aiagent.enabled");
        hide(AI_AGENT_GROUP, "aiagent.base-url");
        hide(AI_AGENT_GROUP, "aiagent.model");
        hide(AI_AGENT_GROUP, "aiagent.api-key");
    }

    void migrateWecom() {
        Connector connector = requireConnector(ConnectorRegistryService.CODE_WECOM);
        Connector update = new Connector();
        update.setId(connector.getId());
        Map<String, Object> extra = extraOf(connector);
        copyText(update::setBaseUrl, connector.getBaseUrl(), item(EMAIL_INTEGRATION_GROUP, "wecom.base-url"),
                "https://qyapi.weixin.qq.com");
        if (!has(connector.getEncryptedToken())) {
            String secret = item(EMAIL_INTEGRATION_GROUP, "wecom.secret");
            if (has(secret)) update.setEncryptedToken(cipher.encrypt(secret));
        }
        if ("true".equalsIgnoreCase(item(EMAIL_INTEGRATION_GROUP, "wecom.enabled"))) update.setEnabled(1);
        putIfAbsent(extra, "corpId", item(EMAIL_INTEGRATION_GROUP, "wecom.corp-id"));
        putIfAbsent(extra, "agentId", item(EMAIL_INTEGRATION_GROUP, "wecom.agent-id"));
        applyExtra(update, connector, extra);
        save(update);
        hide(EMAIL_INTEGRATION_GROUP, "wecom.enabled");
        hide(EMAIL_INTEGRATION_GROUP, "wecom.base-url");
        hide(EMAIL_INTEGRATION_GROUP, "wecom.corp-id");
        hide(EMAIL_INTEGRATION_GROUP, "wecom.agent-id");
        hide(EMAIL_INTEGRATION_GROUP, "wecom.secret");
        // ai-connector 组已全部搬迁（worktime/yuque/mail），整体隐藏
        hideGroup(LEGACY_AI_CONNECTOR_GROUP);
    }

    // ==================== 内部 ====================

    private void migrate(String code, Runnable action) {
        try {
            if ("done".equals(marker(code))) return;
            if (findConnector(code) == null) {
                log.warn("连接器历史配置搬迁跳过（连接器行不存在）: code={}", code);
                return;
            }
            action.run();
            saveMarker(code);
            log.info("连接器历史配置已搬迁: code={}", code);
        } catch (Exception e) {
            log.error("连接器历史配置搬迁失败（下次启动重试）: code={}, error={}", code, e.getMessage(), e);
        }
    }

    private Connector requireConnector(String code) {
        Connector connector = findConnector(code);
        if (connector == null) throw new IllegalStateException("连接器不存在：" + code);
        return connector;
    }

    private Connector findConnector(String code) {
        return connectorMapper.selectOne(new LambdaQueryWrapper<Connector>()
                .eq(Connector::getCode, code)
                .last("LIMIT 1"));
    }

    private void save(Connector update) {
        // 补丁为空（除 id 外全为 null）时不能下发 UPDATE（MyBatis Plus 会生成 `UPDATE ... WHERE id=?` 语法错误）
        if (isEmptyPatch(update)) return;
        connectorMapper.updateById(update);
    }

    /** 除 id 外是否没有任何待写字段。 */
    private boolean isEmptyPatch(Connector patch) {
        try {
            JsonNode node = objectMapper.valueToTree(patch);
            for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (!"id".equals(field) && !node.path(field).isNull()) return false;
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /** 只填空：目标为空（或等于种子默认值）时写入源值。 */
    private void copyText(java.util.function.Consumer<String> setter, String current, String candidate,
                          String... seedDefaults) {
        if (!has(candidate)) return;
        if (!has(current) || matchesAny(current, seedDefaults)) {
            setter.accept(trim(candidate));
        }
    }

    /** 只填空（种子默认值视为空）。 */
    private void putIfAbsent(Map<String, Object> extra, String key, String value, String... seedDefaults) {
        if (!has(value)) return;
        Object current = extra.get(key);
        if (current == null || matchesAny(String.valueOf(current), seedDefaults)) {
            extra.put(key, trim(value));
        }
    }

    private boolean matchesAny(String value, String... seeds) {
        if (!has(value) || seeds == null) return false;
        for (String seed : seeds) {
            if (seed != null && seed.equalsIgnoreCase(value.trim())) return true;
        }
        return false;
    }

    private void applyExtra(Connector update, Connector connector, Map<String, Object> extra) {
        if (extra.isEmpty()) return;
        if (json(extra).equals(connector.getExtraConfig())) return;
        update.setExtraConfig(json(extra));
    }

    private Map<String, Object> extraOf(Connector connector) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (!has(connector.getExtraConfig())) return extra;
        try {
            Map<String, Object> parsed = objectMapper.readValue(connector.getExtraConfig(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            if (parsed != null) extra.putAll(parsed);
        } catch (Exception e) {
            log.warn("连接器扩展参数解析失败: code={}", connector.getCode());
        }
        return extra;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("连接器扩展参数序列化失败", e);
        }
    }

    String item(String group, String key) {
        SystemConfigItem item = configMapper.selectOne(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, group)
                .eq(SystemConfigItem::getConfigKey, key)
                .last("LIMIT 1"));
        if (item == null || !has(item.getConfigValue())) return null;
        return Integer.valueOf(1).equals(item.getIsSensitive())
                ? cipher.decrypt(item.getConfigValue()) : item.getConfigValue();
    }

    void hide(String group, String key) {
        SystemConfigItem item = configMapper.selectOne(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, group)
                .eq(SystemConfigItem::getConfigKey, key)
                .last("LIMIT 1"));
        if (item == null || Integer.valueOf(0).equals(item.getStatus())) return;
        SystemConfigItem update = new SystemConfigItem();
        update.setId(item.getId());
        update.setStatus(0);
        configMapper.updateById(update);
    }

    void hideGroup(String group) {
        for (SystemConfigItem item : configMapper.selectList(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, group))) {
            hide(group, item.getConfigKey());
        }
    }

    private String marker(String code) {
        return item(MARKER_GROUP, code + ".migrated");
    }

    private void saveMarker(String code) {
        SystemConfigItem item = configMapper.selectOne(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, MARKER_GROUP)
                .eq(SystemConfigItem::getConfigKey, code + ".migrated")
                .last("LIMIT 1"));
        if (item == null) {
            log.warn("连接器搬迁标记缺失（迁移脚本未执行？）: key={}.migrated", code);
            return;
        }
        SystemConfigItem update = new SystemConfigItem();
        update.setId(item.getId());
        update.setConfigValue("done");
        configMapper.updateById(update);
    }

    private String normalizeEdition(String value) {
        return has(value) ? value.trim().toLowerCase(java.util.Locale.ROOT) : "center";
    }

    private Integer parseInteger(String value, Integer fallback) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isEnabled(Integer value) {
        return Integer.valueOf(1).equals(value);
    }

    private static boolean has(String value) {
        return StringUtils.hasText(value);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
