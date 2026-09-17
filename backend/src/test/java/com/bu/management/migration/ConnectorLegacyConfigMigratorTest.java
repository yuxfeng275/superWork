package com.bu.management.migration;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.EmailProperties;
import com.bu.management.config.SeeyonOaTokenCipher;
import com.bu.management.config.WorktimeTokenCipher;
import com.bu.management.config.YunxiaoTokenCipher;
import com.bu.management.entity.Connector;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.entity.WorktimeIntegrationConfig;
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
 * 历史连接配置搬迁：单行配置表优先、旧密文换新密文、标记带版本、异常不阻断启动。
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

    private Connector connectorRow(String code) {
        Connector connector = new Connector();
        connector.setId(1L);
        connector.setCode(code);
        connector.setEnabled(0);
        return connector;
    }

    private Connector yunxiaoConnectorRow() {
        Connector connector = connectorRow("yunxiao");
        connector.setBaseUrl("https://openapi-rdc.aliyuncs.com");
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
    @DisplayName("云效：单行配置表是同步链路的历史生效来源，冲突时覆盖连接器上的页面值")
    void migrateYunxiaoLegacyTableWinsOverPageValues() {
        Connector connectorRow = yunxiaoConnectorRow();
        connectorRow.setBaseUrl("https://manual.example.com");
        connectorRow.setEncryptedToken(cipher.encrypt("manual-token"));
        connectorRow.setEnabled(1);
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        YunxiaoIntegrationConfig legacy = new YunxiaoIntegrationConfig();
        legacy.setId(1L);
        legacy.setEnabled(1);
        legacy.setEdition("region");
        legacy.setBaseUrl("https://legacy.example.com");
        legacy.setEncryptedToken("old-cipher-token");
        when(yunxiaoConfigMapper.selectById(1L)).thenReturn(legacy);
        when(yunxiaoCipher.decrypt("old-cipher-token")).thenReturn("legacy-token");

        migrator.migrateYunxiao();

        Connector patch = captureConnectorPatch();
        assertThat(patch.getBaseUrl()).isEqualTo("https://legacy.example.com");
        assertThat(cipher.decrypt(patch.getEncryptedToken())).isEqualTo("legacy-token");
    }

    @Test
    @DisplayName("工时：旧表的服务账号覆盖连接器上失效的页面凭据")
    void migrateWorktimeLegacyCredentialsWinOverRegistry() {
        Connector connectorRow = connectorRow("worktime");
        connectorRow.setBaseUrl("https://worktime.lucidata.cn");
        connectorRow.setEncryptedUsername(cipher.encrypt("page-user"));
        connectorRow.setEncryptedPassword(cipher.encrypt("page-pass"));
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        WorktimeIntegrationConfig legacy = new WorktimeIntegrationConfig();
        legacy.setId(1L);
        legacy.setEnabled(1);
        legacy.setBaseUrl("https://worktime.lucidata.cn");
        legacy.setEncryptedEmployeeNo("legacy-emp-cipher");
        legacy.setEncryptedPassword("legacy-pwd-cipher");
        when(worktimeConfigMapper.selectById(1L)).thenReturn(legacy);
        when(worktimeCipher.decrypt("legacy-emp-cipher")).thenReturn("00504");
        when(worktimeCipher.decrypt("legacy-pwd-cipher")).thenReturn("real-password");

        migrator.migrateWorktime();

        Connector patch = captureConnectorPatch();
        assertThat(cipher.decrypt(patch.getEncryptedUsername())).isEqualTo("00504");
        assertThat(cipher.decrypt(patch.getEncryptedPassword())).isEqualTo("real-password");
        assertThat(patch.getEnabled()).isEqualTo(1);
    }

    @Test
    @DisplayName("语雀：旧配置组的 MCP 地址与 Token 搬入连接器")
    void migrateYuqueCopiesGroupValues() {
        Connector connectorRow = connectorRow("yuque");
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
        Connector connectorRow = connectorRow("deepseek");
        connectorRow.setBaseUrl("https://api.deepseek.com");
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
    @DisplayName("OA：合同导出地址并入连接器扩展参数，旧配置组整体隐藏")
    void migrateOaMovesContractExportUrl() {
        Connector connectorRow = connectorRow("oa");
        connectorRow.setBaseUrl("https://oa.lucidata.cn");
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
    @DisplayName("OA：无旧值可搬时不发起更新（空补丁会生成非法 UPDATE）")
    void migrateOaWithNothingToCopySkipsUpdate() {
        Connector connectorRow = connectorRow("oa");
        connectorRow.setBaseUrl("https://oa.lucidata.cn");
        connectorRow.setEnabled(1);
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        ConnectorLegacyConfigMigrator spy = Mockito.spy(migrator);
        Mockito.lenient().doReturn(null).when(spy).item("oa-vreport", "sales-contract-export-url");

        spy.migrateOa();

        verify(connectorMapper, never()).updateById(any(Connector.class));
    }

    @Test
    @DisplayName("标记为当前规则版本的 done 时不再执行（幂等）")
    void migrationIsSkippedAfterMarkerWritten() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(configItem("done:v2"));

        migrator.run(new DefaultApplicationArguments());

        verify(connectorMapper, never()).updateById(any(Connector.class));
        verify(connectorMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("旧规则版本的标记视为过期，重跑一次（规则升级可纠正历史取值）")
    void migrationRerunsWhenMarkerFromOlderRuleVersion() {
        when(configMapper.selectOne(any(Wrapper.class))).thenReturn(configItem("done"));
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        migrator.run(new DefaultApplicationArguments());

        verify(connectorMapper, Mockito.atLeastOnce()).selectOne(any(Wrapper.class));
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
    @DisplayName("搬迁成功写入带版本标记；标记行缺失时自动补建（隐藏组）")
    void markerIsWrittenWithRuleVersion() {
        Connector connectorRow = yunxiaoConnectorRow();
        when(connectorMapper.selectOne(any(Wrapper.class))).thenReturn(connectorRow);
        when(configMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> configItem(null));

        migrator.run(new DefaultApplicationArguments());

        ArgumentCaptor<SystemConfigItem> captor = ArgumentCaptor.forClass(SystemConfigItem.class);
        verify(configMapper, Mockito.atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(item -> assertThat(item.getConfigValue()).isEqualTo("done:v2"));
    }
}
