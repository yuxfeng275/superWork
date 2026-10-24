package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.integration.YuqueMcpClient;
import com.bu.management.mapper.BizLineProfitReportMapper;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.SalesOpportunityFollowUpMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.mapper.WeeklyReportMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * generate() 的端到端单测：固定模版自检/重试、KPI+商机拼装、落库字段。
 * 模版约定见 docs/weekly-report-template.md。
 */
@ExtendWith(MockitoExtension.class)
class WeeklyReportGenerateTest {

    @Mock private WeeklyReportMapper reportMapper;
    @Mock private DigestLlmClient digestLlmClient;
    @Mock private YuqueMcpClient yuqueClient;
    @Mock private WeComClient weComClient;
    @Mock private BuKeyMatterService keyMatterService;
    @Mock private RevenueContractEntryMapper contractEntryMapper;
    @Mock private BizLineProfitReportMapper profitReportMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private SalesOpportunityFollowUpMapper opportunityFollowUpMapper;
    @Mock private SalesOpportunityMapper salesOpportunityMapper;
    @Mock private SystemConfigService configService;
    @Mock private ConnectorRegistryService registryService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private WeeklyReportService service;

    private static final LocalDate WEEK_START = LocalDate.of(2026, 11, 2);
    private static final String NOTIFY_USER = "yufeng";

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notifyWeComUser", NOTIFY_USER);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "https://bu.example.com");
        WeeklyReport report = new WeeklyReport();
        report.setId(9L);
        report.setWeekStartDate(WEEK_START);
        report.setStatus(WeeklyReport.STATUS_PENDING);
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(keyMatterService.meeting(any())).thenReturn(List.of());
        when(configService.getValue(eq("weekly-report"), eq("core-work.project-groups"), any()))
                .thenReturn("皇家项目,飞鹤,佳贝艾特,海普诺凯,黄天鹅,Speedo,澳优,逢时");
        when(digestLlmClient.candidateCount()).thenReturn(1);
    }

    /** 摘要客户端的成功返回：内容 + 实际使用的模型。 */
    private static DigestLlmClient.ChatResult chat(JsonNode json) {
        return new DigestLlmClient.ChatResult(json, "deepseek", "deepseek-flash");
    }

    @Test
    void savesCompliantTemplateWithoutRetry() throws Exception {
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(chat(compliant()));

        service.generate(WEEK_START);

        verify(digestLlmClient, times(1)).chat(anyString(), anyString(), any());
        WeeklyReport saved = savedReport();
        assertThat(saved.getStatus()).isEqualTo(WeeklyReport.STATUS_DRAFT);
        assertThat(saved.getCoreWork()).contains("皇家项目：").contains("全渠道产品：");
        assertThat(saved.getKpiSection()).contains("KPI：").contains("商机：");
        assertThat(saved.getGenerationError()).isNull();
        assertThat(saved.getGenerationProvider()).isEqualTo("deepseek");
        assertThat(saved.getGenerationModel()).isEqualTo("deepseek-flash");
    }

    @Test
    void retriesOnceWithIssueListWhenTemplateIsBroken() throws Exception {
        ObjectNode broken = compliant();
        broken.put("nextWeekPlan", "- 10/23 前完成飞鹤接口改造");
        ObjectNode fixed = compliant();
        fixed.put("nextWeekPlan", "- 完成飞鹤接口改造");
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenReturn(chat(broken), chat(fixed));

        service.generate(WEEK_START);

        ArgumentCaptor<String> userInput = ArgumentCaptor.forClass(String.class);
        verify(digestLlmClient, times(2)).chat(anyString(), userInput.capture(), any());
        assertThat(userInput.getAllValues().get(1)).contains("不符合固定模版").contains("具体日期");
        assertThat(savedReport().getNextWeekPlan()).isEqualTo("- 完成飞鹤接口改造");
    }

    @Test
    void keepsFirstResultWhenRetryStillBreaksTemplate() throws Exception {
        ObjectNode broken = compliant();
        broken.put("nextWeekPlan", "- 10/23 前完成");
        ObjectNode stillBroken = compliant();
        stillBroken.put("nextWeekPlan", "- 10/24 前完成");
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenReturn(chat(broken), chat(stillBroken));

        service.generate(WEEK_START);

        verify(digestLlmClient, times(2)).chat(anyString(), anyString(), any());
        assertThat(savedReport().getNextWeekPlan()).isEqualTo("- 10/23 前完成");
        assertThat(savedReport().getStatus()).isEqualTo(WeeklyReport.STATUS_DRAFT);
    }

    @Test
    void keepsSystemKpiNumbersAndModelOpportunities() throws Exception {
        BusinessLine line = new BusinessLine();
        line.setId(9L);
        line.setStatus(1);
        line.setKpiReportGroup("会员通");
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));
        BizLineProfitReport row = new BizLineProfitReport();
        row.setBusinessLineId(9L);
        row.setYearMonth("2026-11");
        row.setWorktimeBusinessLineName("全域-全渠道-会员通");
        row.setRevenue(new BigDecimal("100000"));
        row.setGrossProfit(new BigDecimal("60000"));
        when(profitReportMapper.selectList(any())).thenReturn(List.of(row));
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(chat(compliant()));

        service.generate(WEEK_START);

        String kpi = savedReport().getKpiSection();
        assertThat(kpi).contains("KPI：").contains("合计：营收 10.00 万").contains("商机：").contains("皇家 TM 处方签");
        assertThat(kpi).doesNotContain("系统计算");
    }

    @Test
    void keepsGuidanceDrivenKpiWhenProfitTableHasNoCurrentMonth() throws Exception {
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(chat(compliant()));

        service.generate(WEEK_START);

        String kpi = savedReport().getKpiSection();
        assertThat(kpi).contains("KPI：").contains("系统计算").contains("商机：");
    }

    @Test
    void movesOffWhitelistCustomerGroupIntoOthers() throws Exception {
        ObjectNode withExtraGroup = compliant();
        withExtraGroup.put("coreWork",
                "项目：\n  皇家项目：\n  - 积分异常：已定位。下一步：持续监控\n"
                        + "  中升：\n  - MCP 测试：初版完成\n"
                        + "产品：\n  全渠道产品：\n  - 开放平台：第一版 80%\n"
                        + "  海外产品：\n  - EDM MVP：85%\n"
                        + "会员通：\n  - 京东 omid 升级：完成");
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(chat(withExtraGroup));

        service.generate(WEEK_START);

        String coreWork = savedReport().getCoreWork();
        assertThat(coreWork).contains("  皇家项目：").contains("  全渠道产品：").contains("  海外产品：");
        assertThat(coreWork).contains("  其他项目：").contains("- MCP 测试：初版完成");
        assertThat(coreWork).doesNotContain("中升");
    }

    @Test
    void sendsProjectGroupWhitelistToModel() throws Exception {
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenReturn(chat(compliant()));

        service.generate(WEEK_START);

        ArgumentCaptor<String> userInput = ArgumentCaptor.forClass(String.class);
        verify(digestLlmClient).chat(anyString(), userInput.capture(), any());
        assertThat(userInput.getValue()).contains("项目分组白名单")
                .contains("皇家项目、飞鹤")
                .contains("其他项目");
    }

    @Test
    void notifiesWecomAsSoonAsGenerationFails() {
        when(digestLlmClient.chat(anyString(), anyString(), any())).thenThrow(new IllegalStateException(
                "所有摘要模型均失败：bai/deepseek-v4.1-flash 调用失败（经代理 http://mihomo:7890）：request timed out"));

        service.generate(WEEK_START);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(weComClient).pushText(eq(NOTIFY_USER), content.capture());
        assertThat(content.getValue())
                .contains("周报自动生成失败")
                .contains("2026-11-02")
                .contains("所有摘要模型均失败")
                .contains("https://bu.example.com/weekly-report");
        assertThat(savedReport().getStatus()).isEqualTo(WeeklyReport.STATUS_GENERATION_FAILED);
    }

    @Test
    void stillNotifiesWithoutLinkWhenPublicBaseUrlMissing() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "publicBaseUrl", "");
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("所有摘要模型均失败：bai HTTP 402"));

        service.generate(WEEK_START);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(weComClient).pushText(eq(NOTIFY_USER), content.capture());
        assertThat(content.getValue())
                .contains("周报自动生成失败")
                .contains("所有摘要模型均失败")
                .doesNotContain("/weekly-report")
                .contains("未配置 app.public-base-url");
    }

    @Test
    void skipsFailureNoticeWhenNoRecipientConfigured() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notifyWeComUser", "");
        when(digestLlmClient.chat(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("模型全挂了"));

        service.generate(WEEK_START);

        verify(weComClient, org.mockito.Mockito.never()).pushText(anyString(), anyString());
    }

    private WeeklyReport savedReport() {
        ArgumentCaptor<WeeklyReport> captor = ArgumentCaptor.forClass(WeeklyReport.class);
        verify(reportMapper).updateById(captor.capture());
        return captor.getValue();
    }

    /** 符合固定模版的模型输出。 */
    private static ObjectNode compliant() {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("coreWork", "项目：\n  皇家项目：\n  - 积分异常：定位为活动配置。下一步：持续监控\n"
                + "产品：\n  全渠道产品：\n  - 开放平台：第一版 80%。下一步：联调评审\n"
                + "  海外产品：\n  - Lifecycle AI EDM MVP：85%。下一步：验收测试\n"
                + "会员通：\n  - 京东 omid 升级：完成切换");
        node.put("kpiSection", "KPI：\n系统计算\n\n商机：\n- 皇家 TM 处方签：16.5 万中标");
        node.put("risks", "项目：\n  皇家项目：\n  - 风险：差异 4800 项依赖外部协助。解决办法：拉张野协助出分类清单\n"
                + "会员通：\n  - 风险：account 字段缺失致积分中断。解决办法：批量补全工具");
        node.put("nextWeekPlan", "- 完成飞鹤接口改造");
        node.put("minutesMarkdown", "# 电商业务BU周会会议纪要\n**会议周期：** 2026年11月02日 - 2026年11月06日\n\n"
                + "## 下周重点工作计划\n<table><tr><th>序号</th><th>工作项</th><th>预计时间</th></tr></table>");
        return node;
    }
}
