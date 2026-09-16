package com.bu.management.migration;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailProperties;
import com.bu.management.config.SeeyonOaTokenCipher;
import com.bu.management.config.WorktimeTokenCipher;
import com.bu.management.config.YunxiaoTokenCipher;
import com.bu.management.entity.Connector;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.entity.YunxiaoIntegrationConfig;
import com.bu.management.mapper.ConnectorMapper;
import com.bu.management.mapper.SeeyonOaIntegrationConfigMapper;
import com.bu.management.mapper.SystemConfigItemMapper;
import com.bu.management.mapper.WorktimeIntegrationConfigMapper;
import com.bu.management.mapper.YunxiaoIntegrationConfigMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 历史连接配置搬迁：只填空、旧密文换新密文、标记完成后不再执行、异常不阻断启动。
 */
@ExtendWith(MockitoExtension.class)
class ConnectorLegacyConfigMigratorTest {

    @Mock
    private ConnectorMapper connectorMapper;
    @Mock
    private SystemConfigItemMapper configMapper;
    @Mock
    private YunxiaoTokenCipher yunxiaoCipher;
    @Mock
    private WorktimeTokenCipher worktimeCipher;
    @Mock
    private SeeyonOaTokenCipher seeyonCipher;
    @Mock
    private YunxiaoIntegrationConfigMapper yunxiaoConfigMapper;
    @Mock
    private WorktimeIntegrationConfigMapper worktimeConfigMapper;
    @Mock
    private SeeyonOaIntegrationConfigMapper seeyonConfigMapper;

    private final EmailCredentialCipher cipher = new EmailCredentialCipher(credentialProperties());
    private ConnectorLegacyConfigMigrator migrator;

    private static EmailProperties credentialProperties() {
        EmailProperties properties = new EmailProperties();
        properties.setCredentialEncryptionKey(
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }

    @BeforeEach
    void setUp() {
        migrator = new ConnectorLegacyConfigMigrator(connectorMapper, configMapper, cipher,
                new ObjectMapper(), yunxiaoCipher, worktimeCipher, seeyonCipher,
                yunxiaoConfigMapper, worktimeConfigMapper, seeyonConfigMapper);
    }

    private Connector yunxiaoConnectorRow() {
        Connector connector = new Connector();
        connector.setId(1L);
        connector.setCode("yunxiao");
        connector.setBaseUrl("https://openapi-rdc.aliyuncs.com");
        connector.setEnabled(0);
        connector.setExtraConfig("{\"edition\":\"center\"}");
        return connector;
    }

    private Connector captureConnectorPatch() {
        ArgumentCaptor<Connector> captor = ArgumentCaptor.forClass(Connector.class);
        verify(connectorMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private SystemConfigItem configItem(String value) {
        SystemConfigItem item = new SystemConfigItem();
        item.setId(99L);
        item.setConfigValue(value);
        item.setIsSensitive(0);
        item.setStatus(1);
        return item;
    }

    @Test
    @DisplayName("云效：旧表地址/组织 ID/令牌搬入连接器，种子默认值被旧值覆盖，令牌按新 cipher 重加密")
    void migrateYunxiaoCopiesLegacyValues() {
        Connector connectorRow = yunxiaoConnectorRow();
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        YunxiaoIntegrationConfig legacy = new YunxiaoIntegrationConfig();
        legacy.setId(1L);
        legacy.setEnabled(1);
        legacy.setEdition("region");
        legacy.setBaseUrl("https://custom-yunxiao.example.com");
        legacy.setOrganizationId("org-legacy");
        legacy.setEncryptedToken("old-cipher-token");
        when(yunxiaoConfigMapper.selectById(1L)).thenReturn(legacy);
        when(yunxiaoCipher.decrypt("old-cipher-token")).thenReturn("plain-token");

        migrator.migrateYunxiao();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getBaseUrl()).isEqualTo("https://custom-yunxiao.example.com");
        assertThat(patch.getEnabled()).isEqualTo(1);
        assertThat(cipher.decrypt(patch.getEncryptedToken())).isEqualTo("plain-token");
        assertThat(patch.getExtraConfig())
                .contains("\"edition\":\"region\"")
                .contains("\"organizationId\":\"org-legacy\"");
    }

    @Test
    @DisplayName("云效：连接器上已有的人工配置不被旧值覆盖")
    void migrateYunxiaoKeepsManualConfiguration() {
        Connector connectorRow = yunxiaoConnectorRow();
        connectorRow.setBaseUrl("https://manual.example.com");
        connectorRow.setEncryptedToken(cipher.encrypt("manual-token"));
        connectorRow.setEnabled(1);
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        YunxiaoIntegrationConfig legacy = new YunxiaoIntegrationConfig();
        legacy.setId(1L);
        legacy.setEnabled(0);
        legacy.setEdition("center");
        legacy.setBaseUrl("https://legacy.example.com");
        legacy.setEncryptedToken("old-cipher-token");
        when(yunxiaoConfigMapper.selectById(1L)).thenReturn(legacy);

        migrator.migrateYunxiao();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getBaseUrl()).isNull();
        assertThat(patch.getEncryptedToken()).isNull();
        assertThat(patch.getEnabled()).isNull();
        assertThat(cipher.decrypt(connectorRow.getEncryptedToken())).isEqualTo("manual-token");
    }

    @Test
    @DisplayName("语雀：旧配置组的 MCP 地址与 Token 搬入连接器")
    void migrateYuqueCopiesGroupValues() {
        Connector connectorRow = new Connector();
        connectorRow.setId(2L);
        connectorRow.setCode("yuque");
        connectorRow.setBaseUrl("https://lucidata.yuque.com");
        connectorRow.setMcpUrl("https://mcp.yuque.com/mcp");
        connectorRow.setExtraConfig("{\"repo\":\"vuntcs/cf_records\"}");
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        ConnectorLegacyConfigMigrator spy = Mockito.spy(migrator);
        Mockito.lenient().doReturn("https://mcp.custom.com/mcp").when(spy).item("ai-connector", "yuque.mcp-url");
        Mockito.lenient().doReturn("yuque-token-plain").when(spy).item("ai-connector", "yuque.token");
        Mockito.lenient().doReturn("true").when(spy).item("ai-connector", "yuque.enabled");

        spy.migrateYuque();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getMcpUrl()).isEqualTo("https://mcp.custom.com/mcp");
        assertThat(patch.getEnabled()).isEqualTo(1);
        assertThat(cipher.decrypt(patch.getEncryptedToken())).isEqualTo("yuque-token-plain");
    }

    @Test
    @DisplayName("DeepSeek：摘要开关随旧配置搬迁，API Key 换新密文")
    void migrateDeepSeekCarriesDigestFlag() {
        Connector connectorRow = new Connector();
        connectorRow.setId(3L);
        connectorRow.setCode("deepseek");
        connectorRow.setBaseUrl("https://api.deepseek.com");
        connectorRow.setEnabled(0);
        connectorRow.setExtraConfig("{\"model\":\"deepseek-v4-flash\"}");
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        ConnectorLegacyConfigMigrator spy = Mockito.spy(migrator);
        Mockito.lenient().doReturn("true").when(spy).item("email-integration", "deepseek.enabled");
        Mockito.lenient().doReturn("sk-plain").when(spy).item("email-integration", "deepseek.api-key");
        Mockito.lenient().doReturn("deepseek-chat").when(spy).item("email-integration", "deepseek.model");

        spy.migrateDeepSeek();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getEnabled()).isEqualTo(1);
        assertThat(cipher.decrypt(patch.getEncryptedToken())).isEqualTo("sk-plain");
        assertThat(patch.getExtraConfig())
                .contains("\"digestEnabled\":true")
                .contains("\"digestModel\":\"deepseek-chat\"");
    }

    @Test
    @DisplayName("OA：合同导出地址并入连接器扩展参数，旧配置项被隐藏")
    void migrateOaMovesContractExportUrl() {
        Connector connectorRow = new Connector();
        connectorRow.setId(4L);
        connectorRow.setCode("oa");
        connectorRow.setBaseUrl("https://oa.lucidata.cn");
        connectorRow.setEnabled(0);
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        ConnectorLegacyConfigMigrator spy = Mockito.spy(migrator);
        Mockito.lenient().doReturn("https://oa.example.com/report/export").when(spy)
                .item("oa-vreport", "sales-contract-export-url");

        spy.migrateOa();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getExtraConfig()).contains("\"contractExportUrl\":\"https://oa.example.com/report/export\"");
        verify(spy).hideGroup("oa-vreport");
    }

    @Test
    @DisplayName("标记为 done 的搬迁不再执行（幂等）")
    void migrationIsSkippedAfterMarkerWritten() {
        when(configMapper.selectOne(any(Wrapper.class)))
                .thenReturn(configItem("done"));

        migrator.run(new DefaultApplicationArguments());

        verify(connectorMapper, never()).updateById(any(Connector.class));
        verify(connectorMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("库表缺失等异常不阻断启动")
    void databaseFailureIsSwallowed() {
        when(configMapper.selectOne(any(Wrapper.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "sql", new java.sql.SQLException("x")));

        migrator.run(new DefaultApplicationArguments());

        verify(connectorMapper, never()).updateById(any(Connector.class));
    }

    @Test
    @DisplayName("搬迁成功写入 done 标记")
    void markerIsWrittenAfterSuccessfulMigration() {
        Connector connectorRow = yunxiaoConnectorRow();
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        when(configMapper.selectOne(any(Wrapper.class)))
                .thenAnswer(invocation -> configItem(null));

        migrator.run(new DefaultApplicationArguments());

        ArgumentCaptor<SystemConfigItem> captor = ArgumentCaptor.forClass(SystemConfigItem.class);
        verify(configMapper, Mockito.atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(item -> assertThat(item.getConfigValue()).isEqualTo("done"));
    }
}
