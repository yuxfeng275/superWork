package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.integration.WorktimeClient;
import com.bu.management.integration.YuqueMcpClient;

import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ConnectorToolService 分发/裁剪/错误映射单元测试（纯 Mockito，无 DB）。
 */
@ExtendWith(MockitoExtension.class)
class ConnectorToolServiceTest {

    @Mock
    private SystemConfigService configService;
    @Mock
    private AiConnectorIdentityService identityService;
    @Mock
    private EmailMessageMapper emailMessageMapper;
    @Mock
    private EmailAccountMapper emailAccountMapper;
    @Mock
    private YunxiaoWorkItemQueryService yunxiaoQueryService;
    @Mock
    private YunxiaoProjectMappingMapper yunxiaoProjectMappingMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SeeyonOaClient seeyonOaClient;
    @Mock
    private SeeyonOaConfigService seeyonOaConfigService;
    @Mock
    private YunxiaoConfigService yunxiaoConfigService;
    @Mock
    private YuqueMcpClient yuqueMcpClient;
    @Mock
    private WorktimeClient worktimeClient;

    private ConnectorToolService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ConnectorToolService(configService, identityService,
                emailMessageMapper, emailAccountMapper, yunxiaoQueryService,
                yunxiaoProjectMappingMapper, projectMapper, userMapper,
                seeyonOaClient, seeyonOaConfigService, yunxiaoConfigService,
                yuqueMcpClient, worktimeClient, objectMapper);
    }

    private JsonNode args(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EmailMessage message(Long id, Long ownerId, String subject, String body) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setOwnerUserId(ownerId);
        message.setSubject(subject);
        message.setSenderAddress("boss@lucidata.cn");
        message.setBodyPreview(body == null ? null : body.substring(0, Math.min(50, body.length())));
        message.setBodyText(body);
        message.setReceivedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        return message;
    }

    // ==================== 动态裁剪 ====================

    @Test
    @DisplayName("definitions：全部连接器未启用时仅下发邮箱两个工具")
    void definitionsPrunesDisabledConnectors() {
        AiAgentToolDefinition[] defs = service.definitions().toArray(new AiAgentToolDefinition[0]);

        assertThat(defs).extracting(AiAgentToolDefinition::name)
                .containsExactly("search_my_emails", "read_my_email");
    }

    @Test
    @DisplayName("definitions：语雀与工时启用且配置完整时下发对应工具")
    void definitionsIncludesEnabledConnectors() {
        when(yuqueMcpClient.enabled()).thenReturn(true);
        when(yuqueMcpClient.configured()).thenReturn(true);
        when(worktimeClient.enabled()).thenReturn(true);
        when(worktimeClient.configured()).thenReturn(true);
        when(yunxiaoConfigService.getRuntimeConfig()).thenReturn(
                new com.bu.management.config.YunxiaoRuntimeConfig(
                        true, "center", "https://openapi.aliyun.com", "org", "tok", "PAGE", null, null, null));

        List<String> names = service.definitions().stream()
                .map(AiAgentToolDefinition::name).toList();

        assertThat(names).contains(
                "search_yuque_docs", "read_yuque_doc", "query_my_worktime",
                "query_yunxiao_projects", "query_yunxiao_workitems");
    }

    // ==================== 分发与错误映射 ====================

    @Test
    @DisplayName("execute：未知工具返回 isError")
    void unknownToolReturnsError() {
        AiAgentToolResult result = service.execute(7L, "no_such_tool", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("未知工具");
    }

    @Test
    @DisplayName("execute：连接器异常转换为 isError，不抛出")
    void exceptionBecomesIsError() {
        when(emailAccountMapper.selectCount(any())).thenThrow(new IllegalStateException("db down"));

        AiAgentToolResult result = service.execute(7L, "search_my_emails", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).startsWith("工具执行失败：");
    }

    // ==================== 邮箱 ====================

    @Test
    @DisplayName("search_my_emails：未绑定邮箱返回 isError")
    void mailSearchWithoutAccountIsError() {
        when(emailAccountMapper.selectCount(any())).thenReturn(0L);

        AiAgentToolResult result = service.execute(7L, "search_my_emails", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("尚未绑定邮箱");
    }

    @Test
    @DisplayName("search_my_emails：命中邮件列表，content 含 emailId 供后续阅读")
    void mailSearchHappyPath() {
        when(emailAccountMapper.selectCount(any())).thenReturn(1L);
        when(emailMessageMapper.selectList(any())).thenReturn(List.of(
                message(11L, 7L, "项目周报", "本周交付进度正常，详情见附件。")));

        AiAgentToolResult result = service.execute(7L, "search_my_emails",
                args("{\"keyword\":\"周报\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("emailId=11").contains("项目周报");
    }

    @Test
    @DisplayName("read_my_email：只读本人邮件，他人邮件返回 isError")
    void mailReadRejectsOtherOwnersMessage() {
        when(emailAccountMapper.selectCount(any())).thenReturn(1L);
        when(emailMessageMapper.selectOne(any())).thenReturn(null);

        AiAgentToolResult result = service.execute(7L, "read_my_email",
                args("{\"emailId\":99}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("不属于当前用户");
    }

    @Test
    @DisplayName("read_my_email：命中本人邮件返回正文")
    void mailReadHappyPath() {
        when(emailAccountMapper.selectCount(any())).thenReturn(1L);
        when(emailMessageMapper.selectOne(any()))
                .thenReturn(message(11L, 7L, "项目周报", "本周交付进度正常。"));

        AiAgentToolResult result = service.execute(7L, "read_my_email",
                args("{\"emailId\":11}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("项目周报").contains("本周交付进度正常。");
    }

    // ==================== 工时系统 ====================

    @Test
    @DisplayName("query_my_worktime：身份未映射返回固定 isError 文案")
    void worktimeWithoutIdentityIsError() {
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME))
                .thenReturn(null);

        AiAgentToolResult result = service.execute(7L, "query_my_worktime", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content())
                .isEqualTo("未能识别你在工时系统的身份，请联系管理员补录");
        verifyNoInteractions(worktimeClient);
    }

    @Test
    @DisplayName("query_my_worktime：月份格式非法返回 isError")
    void worktimeInvalidMonthIsError() {
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME))
                .thenReturn("E001");
        lenient().when(worktimeClient.normalizeMonth("2026/08"))
                .thenThrow(new IllegalArgumentException("月份格式无效，请使用 YYYY-MM"));

        AiAgentToolResult result = service.execute(7L, "query_my_worktime",
                args("{\"month\":\"2026/08\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("月份格式无效");
    }

    @Test
    @DisplayName("query_my_worktime：命中身份时调用客户端并渲染月度汇总")
    void worktimeHappyPath() {
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME))
                .thenReturn("E001");
        when(worktimeClient.normalizeMonth(null)).thenReturn("2026-09");
        com.fasterxml.jackson.databind.node.ObjectNode data = objectMapper.createObjectNode();
        data.put("status_label", "已提交");
        data.put("total_hours", 160);
        data.put("detail_count", 2);
        when(worktimeClient.employeeMonthly("E001", "2026-09")).thenReturn(data);
        when(worktimeClient.renderMonthly(data, "2026-09")).thenReturn("工时系统 2026-09 月度汇总：状态=已提交，合计=160 小时");

        AiAgentToolResult result = service.execute(7L, "query_my_worktime", args(null));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("2026-09").contains("160");
    }
}
