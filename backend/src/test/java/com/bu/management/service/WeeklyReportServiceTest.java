package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.WeeklyReport;
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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeeklyReportServiceTest {

    @Mock private WeeklyReportMapper reportMapper;
    @Mock private YuqueMcpClient yuqueClient;
    @Mock private SystemConfigService configService;
    @Mock private BuKeyMatterService keyMatterService;
    @Mock private RevenueContractEntryMapper contractEntryMapper;
    @Mock private BizLineProfitReportMapper profitReportMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private SalesOpportunityFollowUpMapper opportunityFollowUpMapper;
    @Mock private SalesOpportunityMapper salesOpportunityMapper;

    @InjectMocks private WeeklyReportService service;

    private WeeklyReport report;

    @BeforeEach
    void setUp() {
        report = new WeeklyReport();
        report.setId(3L);
        report.setWeekStartDate(LocalDate.of(2026, 9, 14));
        report.setPeriodEndDate(LocalDate.of(2026, 9, 18));
        report.setYuqueDocUrl("https://lucidata.yuque.com/vuntcs/cf_records/abc");
        report.setStatus(WeeklyReport.STATUS_PUBLISHED);
    }

    @Test
    void generationPromptRequiresWecomAndKeepsDetail() {
        String prompt = service.generationSystemPrompt();
        assertThat(prompt).contains("企微智能总结");
        assertThat(prompt).contains("人为补充");
        assertThat(prompt).contains("最多 12 条");
        assertThat(prompt).contains("1500 字");
        assertThat(prompt).contains("系统计算");
        assertThat(prompt).doesNotContain("全篇最多 5 条");
        assertThat(prompt).doesNotContain("千人千面");
        assertThat(prompt).doesNotContain("各最多 4 条");
        assertThat(prompt).contains("商机");
        assertThat(prompt).contains("opportunities");
        assertThat(prompt).contains("生成指引");
        assertThat(prompt).contains("优先级最高");
        assertThat(prompt).contains("没有「系统 KPI」段落时");
        assertThat(prompt).contains("项目：");
        assertThat(prompt).contains("产品：");
        assertThat(prompt).contains("会员通：");
        assertThat(prompt).contains("全渠道产品");
        assertThat(prompt).contains("海外产品");
        assertThat(prompt).contains("商机：");
        assertThat(prompt).contains("不要出现任何具体日期");
    }

    @Test
    void kpiSectionUsesManagedBusinessLinesOnly() {
        BusinessLine line = new BusinessLine();
        line.setId(9L);
        line.setStatus(1);
        line.setKpiReportGroup("会员通");
        when(businessLineMapper.selectList(any())).thenReturn(List.of(line));

        BizLineProfitReport current = new BizLineProfitReport();
        current.setBusinessLineId(9L);
        current.setYearMonth("2026-10");
        current.setWorktimeBusinessLineName("全域-全渠道-会员通");
        current.setRevenue(new BigDecimal("100000"));
        current.setGrossProfit(BigDecimal.ZERO);
        BizLineProfitReport ignored = new BizLineProfitReport();
        ignored.setBusinessLineId(3L);
        ignored.setYearMonth("2026-10");
        ignored.setWorktimeBusinessLineName("其他公司线");
        ignored.setRevenue(new BigDecimal("999999"));
        ignored.setGrossProfit(BigDecimal.ZERO);
        when(profitReportMapper.selectList(any())).thenReturn(List.of(current, ignored));

        String text = service.buildKpiSection(LocalDate.of(2026, 10, 5));

        assertThat(text).contains("会员通：营收 10.00 万，毛利 10.00 万");
        assertThat(text).doesNotContain("其他公司线");
        assertThat(text).doesNotContain("全域-全渠道-");
    }

    @Test
    void distillFactsKeepsRisksAndDropsRoutine() {
        java.util.Map<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("finance", java.util.Map.of("newContractAmount", 1));
        facts.put("lastWeekReport", java.util.Map.of("exists", false));
        facts.put("keyMatters", java.util.List.of(
                java.util.Map.of(
                        "title", "皇家积分切换",
                        "priority", "P0",
                        "status", "有风险",
                        "currentWeek", true,
                        "progressSummary", "订单总数不一致，差额较大"),
                java.util.Map.of(
                        "title", "日常巡检",
                        "priority", "P2",
                        "status", "推进中",
                        "currentWeek", false,
                        "progressSummary", "按原计划继续")));

        java.util.Map<String, Object> distilled = service.distillFactsForGeneration(facts);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> matters =
                (java.util.List<java.util.Map<String, Object>>) distilled.get("keyMatters");
        assertThat(matters).hasSize(1);
        assertThat(matters.get(0).get("title")).isEqualTo("皇家积分切换");
    }

    @Test
    void collectFactsIncludesOpportunityFollowUps() {
        when(keyMatterService.meeting(any())).thenReturn(List.of());
        SalesOpportunityFollowUp followUp = new SalesOpportunityFollowUp();
        followUp.setId(21L);
        followUp.setOpportunityId(12L);
        followUp.setFollower("姜涛");
        followUp.setContent("客户确认一期预算，准备出商务条款");
        followUp.setStatus("商务谈判");
        followUp.setProbability(70);
        followUp.setFollowUpAt(LocalDateTime.of(2026, 9, 16, 10, 0));
        followUp.setNextFollowUp("周五评审");
        when(opportunityFollowUpMapper.selectList(any())).thenReturn(List.of(followUp));
        SalesOpportunity opportunity = new SalesOpportunity();
        opportunity.setId(12L);
        opportunity.setName("飞鹤-SCRM系统采购");
        opportunity.setCustomer("飞鹤乳业");
        opportunity.setOwner("姜涛");
        when(salesOpportunityMapper.selectBatchIds(any())).thenReturn(List.of(opportunity));

        java.util.Map<String, Object> facts = service.collectFacts(LocalDate.of(2026, 9, 14));

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> opportunities =
                (java.util.List<java.util.Map<String, Object>>) facts.get("opportunities");
        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0).get("opportunityName")).isEqualTo("飞鹤-SCRM系统采购");
        assertThat(opportunities.get(0).get("content")).asString().contains("一期预算");
    }

    @Test
    void fillSheetMarksDoneWithoutWritingWhenManual() {
        when(reportMapper.selectById(3L)).thenReturn(report);
        when(configService.getValue(eq("weekly-report"), eq("sheet.mode"), any()))
                .thenReturn("MANUAL");

        WeeklyReport updated = service.fillSheet(3L);

        assertThat(updated.getSheetSyncStatus()).isEqualTo("MANUAL_DONE");
        assertThat(updated.getSheetSyncedAt()).isNotNull();
        verify(yuqueClient, never()).writeSheetRow(any(), any(), any(), any(), any(), any(), any(), any());
        verify(reportMapper).updateById(report);
    }

    @Test
    void fillSheetWritesMinutesLinkWhenApiMode() {
        when(reportMapper.selectById(3L)).thenReturn(report);
        when(configService.getValue(eq("weekly-report"), eq("sheet.team-name"), any()))
                .thenReturn("电商业务BU");
        when(configService.getValue(eq("weekly-report"), eq("sheet.sheet-name"), any()))
                .thenReturn("电商业务");
        when(configService.getValue(eq("weekly-report"), eq("sheet.doc-slug"), any()))
                .thenReturn("staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        when(configService.getValue(eq("weekly-report"), eq("sheet.mode"), any()))
                .thenReturn("API");
        when(yuqueClient.writeSheetRow(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new YuqueMcpClient.SheetWriteResult(true, "ok"));

        WeeklyReport updated = service.fillSheet(3L);

        assertThat(updated.getSheetSyncStatus()).isEqualTo("MANUAL_DONE");
        verify(yuqueClient).writeSheetRow(
                eq("API"),
                any(),
                any(),
                eq("staff-qvc012/mghdgg/tyavbayo9ir7tyrk"),
                eq("电商业务"),
                eq("09.14-09.18"),
                eq("电商业务BU"),
                eq("https://lucidata.yuque.com/vuntcs/cf_records/abc"));
    }

    @Test
    void fillSheetRequiresPublishedMinutes() {
        report.setYuqueDocUrl(null);
        when(reportMapper.selectById(3L)).thenReturn(report);

        assertThatThrownBy(() -> service.fillSheet(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语雀");
    }

    @Test
    void buildUserInputKeepsGuidanceAndSystemKpi() {
        report.setWecomSummary("企微智能总结正文");
        report.setManualNotes("人为补充正文");
        report.setGenerationPrompt("KPI 相关情况：9 月数据已与财报拉齐，YTD 毛利 44 万");

        String input = service.buildUserInput(report, "{\"weekStart\":\"2026-10-12\"}", "合计：营收 10.00 万",
                List.of("皇家项目", "飞鹤"));

        assertThat(input).contains("企微智能总结正文");
        assertThat(input).contains("人为补充正文");
        assertThat(input).contains("KPI 相关情况：9 月数据已与财报拉齐，YTD 毛利 44 万");
        assertThat(input).contains("== 系统 KPI");
        assertThat(input).contains("合计：营收 10.00 万");
        assertThat(input).contains("项目分组白名单").contains("皇家项目、飞鹤");
        // 生成指引排在最后，权重最高
        assertThat(input.indexOf("生成指引")).isGreaterThan(input.indexOf("系统 KPI"));
    }

    @Test
    void buildUserInputOmitsSystemKpiWhenProfitTableHasNoCurrentMonth() {
        report.setGenerationPrompt("按 9 月口径写 KPI：YTD 毛利 44 万");

        String input = service.buildUserInput(report, "{}", null, List.of());

        assertThat(input).doesNotContain("系统 KPI");
        assertThat(input).doesNotContain("项目分组白名单");
        assertThat(input).contains("按 9 月口径写 KPI：YTD 毛利 44 万");
    }

    @Test
    void composeKpiSectionKeepsModelKpiWhenSystemHasNoNumbers() {
        String model = "KPI：\n9月数据已与财报拉齐，YTD 毛利 44 万\n\n商机：\n- 皇家 TM 处方签：16.5 万中标";

        String section = WeeklyReportService.composeKpiSection(null, model);

        assertThat(section).contains("KPI：");
        assertThat(section).contains("YTD 毛利 44 万");
        assertThat(section).contains("商机：");
        assertThat(section).contains("皇家 TM 处方签");
    }

    @Test
    void composeKpiSectionKeepsSystemNumbersAndModelOpportunities() {
        String model = "KPI：\n系统计算\n\n商机：\n- 库博全渠道：进入招投标阶段";

        String section = WeeklyReportService.composeKpiSection("合计：营收 10.00 万，毛利 3.23 万", model);

        assertThat(section).contains("KPI：\n合计：营收 10.00 万，毛利 3.23 万");
        assertThat(section).contains("商机：");
        assertThat(section).contains("库博全渠道");
        assertThat(section).doesNotContain("系统计算");
    }

    @Test
    void composeKpiSectionFallsBackToRawTextWhenNothingParses() {
        String model = "本周 KPI 与商机均无变化。";

        assertThat(WeeklyReportService.composeKpiSection(null, model)).isEqualTo(model);
    }

    @Test
    void templateIssuesAcceptsConfirmedTemplate() throws Exception {
        JsonNode result = templateJson(CONFIRMED_CORE_WORK,
                "KPI：\n系统计算\n\n商机：\n- 皇家 TM 处方签：16.5 万中标",
                "项目：\n  皇家项目：\n  - 标品升级 4800 差异。解决办法：拉张野协助出分类清单",
                "- 输出云鹿 OS 场景清单");

        assertThat(WeeklyReportService.templateIssues(result)).isEmpty();
    }

    @Test
    void templateIssuesFlagsMissingHeadersAndConcreteDates() throws Exception {
        JsonNode result = templateJson("皇家项目：积分异常",
                "9 月数据已与财报拉齐",
                "标品升级有风险",
                "- 10/23 前完成飞鹤接口改造");

        assertThat(WeeklyReportService.templateIssues(result))
                .anyMatch(issue -> issue.contains("coreWork"))
                .anyMatch(issue -> issue.contains("kpiSection 缺少「商机：」"))
                .anyMatch(issue -> issue.contains("risks"))
                .anyMatch(issue -> issue.contains("具体日期"));
    }

    @Test
    void templateIssuesRejectsOpportunitiesInsideCoreWork() throws Exception {
        JsonNode result = templateJson(CONFIRMED_CORE_WORK + "\n商机：\n- 皇家 TM 处方签",
                "KPI：\n系统计算\n\n商机：\n- 皇家 TM 处方签",
                "会员通：\n  - 京东 account 缺失。解决办法：批量补全工具",
                "- 完成验收测试");

        assertThat(WeeklyReportService.templateIssues(result))
                .anyMatch(issue -> issue.contains("coreWork 不应再出现"));
    }

    @Test
    void normalizeGroupsMovesOffWhitelistCustomersIntoOthers() {
        String text = "项目：\n  皇家项目：\n- 积分异常：已定位。下一步：持续监控\n"
                + "  中升：\n- MCP 测试：初版完成\n  合生元：\n- 数据看板：需求已对齐\n"
                + "产品：\n  全渠道产品：\n- 开放平台：第一版 80%";

        String normalized = WeeklyReportService.normalizeGroups(text, List.of("皇家项目", "飞鹤"));

        assertThat(normalized).contains("  皇家项目：").contains("  全渠道产品：");
        assertThat(normalized).contains("  其他项目：");
        assertThat(normalized).contains("- MCP 测试：初版完成").contains("- 数据看板：需求已对齐");
        assertThat(normalized).doesNotContain("中升").doesNotContain("合生元");
        // 多个白名单外分组合并成一个「其他项目：」
        assertThat(normalized.split("其他项目：", -1)).hasSize(2);
    }

    @Test
    void normalizeGroupsKeepsExistingOthersGroupOnce() {
        String text = "项目：\n  其他项目：\n- MCP 测试：初版完成\n  中升：\n- 数据看板：需求已对齐";

        String normalized = WeeklyReportService.normalizeGroups(text, List.of("皇家项目"));

        assertThat(normalized).contains("  其他项目：")
                .contains("- MCP 测试：初版完成")
                .contains("- 数据看板：需求已对齐");
        assertThat(normalized).doesNotContain("中升");
        assertThat(normalized.split("其他项目：", -1)).hasSize(2);
    }

    @Test
    void normalizeGroupsRestrictsProductAndMemberGroups() {
        String text = "产品：\n  云鹿AI：\n- 鼠标跟随：设计 40%\n  海外产品：\n- EDM MVP：85%\n"
                + "会员通：\n  京东：\n- 积分中断：已打补丁";

        String normalized = WeeklyReportService.normalizeGroups(text, List.of("皇家项目"));

        assertThat(normalized).doesNotContain("云鹿AI").doesNotContain("  京东：");
        assertThat(normalized).contains("  海外产品：")
                .contains("- 鼠标跟随：设计 40%")
                .contains("- 积分中断：已打补丁");
    }

    @Test
    void normalizeGroupsKeepsCompliantTextUntouched() {
        String text = "项目：\n  皇家项目：\n  - 积分异常：已定位。下一步：持续监控\n"
                + "产品：\n  全渠道产品：\n  - 开放平台：80%\n  海外产品：\n  - EDM MVP：85%\n"
                + "会员通：\n  - omid 升级：完成";

        assertThat(WeeklyReportService.normalizeGroups(text, List.of("皇家项目"))).isEqualTo(text);
    }

    @Test
    void projectWhitelistComesFromProjectMasterData() {
        BusinessLine saas = new BusinessLine();
        saas.setId(2L);
        when(businessLineMapper.selectList(any())).thenReturn(List.of(saas));
        Project royal = new Project();
        royal.setId(1L);
        royal.setName("皇家项目");
        royal.setLevel(1);
        Project feihe = new Project();
        feihe.setId(7L);
        feihe.setName("飞鹤");
        feihe.setLevel(1);
        when(projectMapper.selectList(any())).thenReturn(List.of(royal, feihe));

        assertThat(service.projectGroupWhitelist()).containsExactly("皇家项目", "飞鹤");
    }

    private static final String CONFIRMED_CORE_WORK =
            "项目：\n  皇家项目：\n  - 积分异常：定位为活动配置。下一步：持续监控\n"
                    + "产品：\n  全渠道产品：\n  - 开放平台：第一版 80%。下一步：联调评审\n"
                    + "  海外产品：\n  - Lifecycle AI EDM MVP：85%。下一步：验收测试\n"
                    + "会员通：\n  - 京东 omid 升级：完成切换";

    private static JsonNode templateJson(String coreWork, String kpi, String risks, String nextPlan) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("coreWork", coreWork);
        node.put("kpiSection", kpi);
        node.put("risks", risks);
        node.put("nextWeekPlan", nextPlan);
        node.put("minutesMarkdown", "# 电商业务BU周会会议纪要\n**会议周期：** ...");
        return node;
    }
}
