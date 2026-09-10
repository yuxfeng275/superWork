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
import com.bu.management.service.ConnectorToolService.ConnectorStatus;
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
    @Mock
    private com.bu.management.service.WorktimeAnalyticsService worktimeAnalyticsService;
    @Mock
    private com.bu.management.service.SysRoleService sysRoleService;
    @Mock
    private WorktimeInsightToolService worktimeInsightToolService;

    private ConnectorToolService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ConnectorToolService(configService, identityService,
                emailMessageMapper, emailAccountMapper, yunxiaoQueryService,
                yunxiaoProjectMappingMapper, projectMapper, userMapper,
                seeyonOaClient, seeyonOaConfigService, yunxiaoConfigService,
                yuqueMcpClient, worktimeClient, worktimeAnalyticsService,
                sysRoleService, objectMapper, worktimeInsightToolService);
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

    private JsonNode args(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("definitions：语雀与工时启用且配置完整时下发对应工具")
    void definitionsIncludesEnabledConnectors() {
        when(yuqueMcpClient.enabled()).thenReturn(true);
        when(yuqueMcpClient.configured()).thenReturn(true);
        lenient().when(worktimeClient.enabled()).thenReturn(true);
        lenient().when(worktimeClient.configured()).thenReturn(true);
        when(yunxiaoConfigService.getRuntimeConfig()).thenReturn(
                new com.bu.management.config.YunxiaoRuntimeConfig(
                        true, "center", "https://openapi.aliyun.com", "org", "tok", "PAGE", null, null, null));

        List<String> names = service.definitions().stream()
                .map(AiAgentToolDefinition::name).toList();

        assertThat(names).contains(
                "search_yuque_docs", "read_yuque_doc", "query_my_worktime",
                "query_yunxiao_projects", "query_yunxiao_workitems", "get_yunxiao_workitem");
    }
    @Test
    @DisplayName("handles：内置工具名识别，含新增的 get_yunxiao_workitem")
    void handlesRecognizesBuiltinTools() {
        assertThat(service.handles("get_yunxiao_workitem")).isTrue();
        assertThat(service.handles("get_oa_flow")).isTrue();
        assertThat(service.handles("no_such_tool")).isFalse();
    }
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

    @Test
    @DisplayName("statuses：工时系统恒就绪（读本地同步库）")
    void statusesWorktimeAlwaysReady() {
        ConnectorStatus wt = service.statuses().stream()
                .filter(s -> "worktime".equals(s.code())).findFirst().orElseThrow();

        assertThat(wt.status()).isEqualTo("READY");
    }

    @Test
    @DisplayName("statuses：语雀启用但未配置时为 NOT_CONFIGURED")
    void statusesYuqueEnabledNotConfigured() {
        lenient().when(yuqueMcpClient.enabled()).thenReturn(true);
        lenient().when(yuqueMcpClient.configured()).thenReturn(false);

        ConnectorStatus yuque = service.statuses().stream()
                .filter(s -> "yuque".equals(s.code())).findFirst().orElseThrow();

        assertThat(yuque.status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    @DisplayName("statuses：语雀未启用时为 DISABLED")
    void statusesYuqueDisabled() {
        lenient().when(yuqueMcpClient.enabled()).thenReturn(false);
        lenient().when(yuqueMcpClient.configured()).thenReturn(false);

        ConnectorStatus yuque = service.statuses().stream()
                .filter(s -> "yuque".equals(s.code())).findFirst().orElseThrow();

        assertThat(yuque.status()).isEqualTo("DISABLED");
    }

    // ==================== 工时系统（本地同步库分析） ====================

    private com.bu.management.service.WorktimeAnalyticsService.MonthlyReport report(
            String month, String name, int entries, String hours) {
        var rows = new java.util.ArrayList<com.bu.management.service.WorktimeAnalyticsService.MonthRow>();
        var projectHours = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        var lineHours = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        var typeHours = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        if (entries > 0) {
            rows.add(new com.bu.management.service.WorktimeAnalyticsService.MonthRow(
                    "皇家宠物项目【交付】", "电商业务BU", "project", new java.math.BigDecimal(hours), "x"));
            projectHours.put("皇家宠物项目【交付】", new java.math.BigDecimal(hours));
            lineHours.put("电商业务BU", new java.math.BigDecimal(hours));
            typeHours.put("project", new java.math.BigDecimal(hours));
        }
        return new com.bu.management.service.WorktimeAnalyticsService.MonthlyReport(
                month, "E001", name, new java.math.BigDecimal(hours), entries, rows.size(),
                rows, projectHours, lineHours, typeHours, null);
    }

    @Test
    @DisplayName("query_my_worktime：身份未解析返回 isError 引导补录")
    void worktimeWithoutIdentityIsError() {
        when(worktimeAnalyticsService.personalMonthly(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);

        AiAgentToolResult result = service.execute(7L, "query_my_worktime", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("未能识别你在工时系统的身份");
        verifyNoInteractions(worktimeClient);
    }

    @Test
    @DisplayName("query_my_worktime：月份格式非法返回 isError")
    void worktimeInvalidMonthIsError() {
        AiAgentToolResult result = service.execute(7L, "query_my_worktime",
                args("{\"month\":\"2026/08\"}"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("月份格式无效");
    }

    @Test
    @DisplayName("query_my_worktime：默认上个月，渲染汇总与项目明细")
    void worktimeHappyPath() {
        String month = java.time.YearMonth.now().minusMonths(1).toString();
        when(worktimeAnalyticsService.personalMonthly(7L, month)).thenReturn(report(month, "石家乐", 6, "42.50"));

        AiAgentToolResult result = service.execute(7L, "query_my_worktime", args(null));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains(month).contains("42.50").contains("皇家宠物项目");
    }

    @Test
    @DisplayName("query_my_worktime：无填报记录返回可用提示")
    void worktimeEmptyMonth() {
        String month = java.time.YearMonth.now().minusMonths(1).toString();
        when(worktimeAnalyticsService.personalMonthly(7L, month)).thenReturn(report(month, "石家乐", 0, "0"));

        AiAgentToolResult result = service.execute(7L, "query_my_worktime", args(null));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("无填报记录");
    }

    @Test
    @DisplayName("analyze_my_worktime：输出趋势与环比")
    void analyzeMyWorktimeTrend() {
        var trend = List.of(
                new com.bu.management.service.WorktimeAnalyticsService.MonthPoint("2026-07", new java.math.BigDecimal("23.10"), 93),
                new com.bu.management.service.WorktimeAnalyticsService.MonthPoint("2026-08", new java.math.BigDecimal("13.70"), 84));
        when(worktimeAnalyticsService.personalTrend(7L, 2)).thenReturn(trend);
        when(worktimeAnalyticsService.personalMonthly(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(report("2026-08", "石家乐", 84, "13.70"));

        AiAgentToolResult result = service.execute(7L, "analyze_my_worktime",
                args("{\"months\":2,\"month\":\"2026-08\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("2026-07").contains("2026-08")
                .contains("环比").contains("项目分布");
    }

    @Test
    @DisplayName("analyze_team_worktime：无 revenue:view 权限返回 isError")
    void teamWorktimeRequiresPermission() {
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("email:view"));

        AiAgentToolResult result = service.execute(7L, "analyze_team_worktime", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("营收查看");
    }

    @Test
    @DisplayName("analyze_team_worktime：有权限时输出业务线汇总与未填报成员")
    void teamWorktimeHappyPath() {
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("revenue:view"));
        String month = java.time.YearMonth.now().minusMonths(1).toString();
        when(worktimeAnalyticsService.teamMonthly(month)).thenReturn(
                new com.bu.management.service.WorktimeAnalyticsService.TeamMonthlyReport(
                        month,
                        List.of(new com.bu.management.service.WorktimeAnalyticsService.TeamLineRow(
                                "电商业务BU", new java.math.BigDecimal("120.50"), 12, 84)),
                        new java.math.BigDecimal("120.50"), 84));
        when(worktimeAnalyticsService.missingReportUsers(month)).thenReturn(List.of("王缓", "乔倩"));

        AiAgentToolResult result = service.execute(7L, "analyze_team_worktime", args(null));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("电商业务BU").contains("120.50")
                .contains("未填报成员").contains("王缓");
    }
}
