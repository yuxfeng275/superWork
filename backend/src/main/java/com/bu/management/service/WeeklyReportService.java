package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Connector;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
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
import com.bu.management.vo.BuKeyMatterView;
import com.bu.management.vo.BuKeyMatterWeeklyUpdateView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BG 周报与周会纪要：事实采集、AI 生成、语雀发布、企微提醒、汇总表回填标记。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportService {

    private static final String CONFIG_GROUP = "weekly-report";
    private static final String MINUTES_TITLE = "# 电商业务BU周会会议纪要";
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 「产品：」段只允许的两个二级分类。 */
    private static final List<String> PRODUCT_GROUPS = List.of("全渠道产品", "海外产品");
    /** 白名单外客户事项的兜底分组名。 */
    private static final String OTHERS_GROUP = "其他项目";
    /** 一级标题固定名称（coreWork / kpiSection / risks 共用）。 */
    private static final List<String> LEVEL1_GROUPS = List.of("项目", "产品", "会员通", "KPI", "商机");
    /** 项目主数据读不到时的项目分组白名单兜底。 */
    private static final List<String> DEFAULT_PROJECT_GROUPS =
            List.of("皇家项目", "飞鹤", "佳贝艾特", "海普诺凯", "黄天鹅", "Speedo", "澳优", "逢时");

    private final WeeklyReportMapper reportMapper;
    private final DigestLlmClient digestLlmClient;
    private final YuqueMcpClient yuqueClient;
    private final WeComClient weComClient;
    private final BuKeyMatterService keyMatterService;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final BizLineProfitReportMapper profitReportMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final SalesOpportunityFollowUpMapper opportunityFollowUpMapper;
    private final SalesOpportunityMapper salesOpportunityMapper;
    private final SystemConfigService configService;

    private final ConnectorRegistryService registryService;

    private final ObjectMapper objectMapper;

    @Resource(name = "emailTaskExecutor")
    private Executor taskExecutor;

    /** 生成失败时的企微告警接收人（与周五自动生成用同一个配置）。 */
    @Value("${weekly.report-notify-wecom-user:}")
    private String notifyWeComUser;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    // ==================== 基础 ====================

    /** 获取或创建指定周（weekStart=周一）的周报记录。 */
    public WeeklyReport getOrCreate(LocalDate weekStart) {
        validateWeekStart(weekStart);
        WeeklyReport existing = reportMapper.selectOne(new LambdaQueryWrapper<WeeklyReport>()
                .eq(WeeklyReport::getWeekStartDate, weekStart));
        if (existing != null) {
            return existing;
        }
        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(weekStart);
        report.setPeriodEndDate(weekStart.plusDays(4));
        report.setStatus(WeeklyReport.STATUS_PENDING);
        try {
            reportMapper.insert(report);
        } catch (Exception e) {
            // 唯一键并发冲突：重新读取
            return reportMapper.selectOne(new LambdaQueryWrapper<WeeklyReport>()
                    .eq(WeeklyReport::getWeekStartDate, weekStart));
        }
        return report;
    }

    public WeeklyReport getById(Long id) {
        WeeklyReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new IllegalArgumentException("周报不存在");
        }
        return report;
    }

    public List<WeeklyReport> history(int limit) {
        return reportMapper.selectList(new LambdaQueryWrapper<WeeklyReport>()
                .orderByDesc(WeeklyReport::getWeekStartDate)
                .last("LIMIT " + Math.max(1, Math.min(limit, 50))));
    }

    /** 保存人工输入（企微智能总结 + 补充信息 + 生成提示词）。 */
    public WeeklyReport saveInputs(Long reportId, String wecomSummary, String manualNotes,
                                 String generationPrompt) {
        WeeklyReport report = getById(reportId);
        if (wecomSummary != null) {
            report.setWecomSummary(wecomSummary);
        }
        if (manualNotes != null) {
            report.setManualNotes(manualNotes);
        }
        if (generationPrompt != null) {
            report.setGenerationPrompt(generationPrompt);
        }
        reportMapper.updateById(report);
        return report;
    }

    /** 保存编辑后的内容（4 段 + 纪要）。 */
    public WeeklyReport saveContent(Long reportId, String coreWork, String kpiSection,
                                    String risks, String nextWeekPlan, String minutesMarkdown) {
        WeeklyReport report = getById(reportId);
        if (WeeklyReport.STATUS_PUBLISHED.equals(report.getStatus())) {
            throw new IllegalStateException("周报已发布，不可再编辑");
        }
        report.setCoreWork(coreWork);
        report.setKpiSection(kpiSection);
        report.setRisks(risks);
        report.setNextWeekPlan(nextWeekPlan);
        report.setMinutesMarkdown(minutesMarkdown);
        if (WeeklyReport.STATUS_PENDING.equals(report.getStatus())
                || WeeklyReport.STATUS_GENERATION_FAILED.equals(report.getStatus())) {
            report.setStatus(WeeklyReport.STATUS_DRAFT);
        }
        reportMapper.updateById(report);
        return report;
    }

    /** 人工确认草稿。 */
    public WeeklyReport confirm(Long reportId) {
        WeeklyReport report = getById(reportId);
        if (!hasContent(report)) {
            throw new IllegalStateException("周报内容为空，请先生成或填写后再确认");
        }
        report.setStatus(WeeklyReport.STATUS_CONFIRMED);
        reportMapper.updateById(report);
        return report;
    }

    // ==================== 事实采集 ====================

    /** 采集自动事实：大事儿进度 + 商机跟进 + 财务 + 上周闭环。 */
    public Map<String, Object> collectFacts(LocalDate weekStart) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("weekStart", weekStart.toString());
        facts.put("periodEnd", weekStart.plusDays(4).toString());
        facts.put("keyMatters", collectKeyMatters(weekStart));
        facts.put("opportunities", collectOpportunityFollowUps(weekStart));
        try {
            facts.put("finance", collectFinance(weekStart));
        } catch (Exception e) {
            log.warn("财务周报数据采集失败: {}", e.getMessage());
            facts.put("finance", Map.of("month", weekStart.format(MONTH_FMT),
                    "newContractAmount", 0, "deliveredAmount", 0, "cumulativeReceivable", 0));
        }
        facts.put("lastWeekReport", collectLastWeek(weekStart));
        return facts;
    }

    private List<Map<String, Object>> collectKeyMatters(LocalDate weekStart) {
        List<BuKeyMatterView> matters;
        try {
            matters = keyMatterService.meeting(weekStart);
        } catch (Exception e) {
            log.warn("大事儿周报数据采集失败: {}", e.getMessage());
            return List.of();
        }
        return matters.stream().map(matter -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", matter.getId());
            row.put("title", matter.getTitle());
            row.put("projectName", matter.getProjectName());
            row.put("ownerName", matter.getOwnerName());
            row.put("priority", matter.getPriority());
            row.put("status", matter.getStatus());
            row.put("progress", matter.getProgress());
            row.put("currentWeek", matter.getCurrentWeekUpdate() != null);
            BuKeyMatterWeeklyUpdateView update = matter.getCurrentWeekUpdate() != null
                    ? matter.getCurrentWeekUpdate() : matter.getLatestUpdate();
            if (update != null) {
                row.put("progressSummary", clip(update.getProgressSummary(), 200));
                row.put("issues", clip(update.getIssues(), 160));
                row.put("nextWeekPlan", clip(update.getNextWeekPlan(), 160));
                row.put("supportNeeded", clip(update.getSupportNeeded(), 120));
            }
            return row;
        }).toList();
    }

    private List<Map<String, Object>> collectOpportunityFollowUps(LocalDate weekStart) {
        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = weekStart.plusDays(7).atStartOfDay();
        List<SalesOpportunityFollowUp> followUps;
        try {
            followUps = opportunityFollowUpMapper.selectList(new LambdaQueryWrapper<SalesOpportunityFollowUp>()
                    .ge(SalesOpportunityFollowUp::getFollowUpAt, from)
                    .lt(SalesOpportunityFollowUp::getFollowUpAt, to)
                    .orderByDesc(SalesOpportunityFollowUp::getFollowUpAt)
                    .last("LIMIT 40"));
        } catch (Exception e) {
            log.warn("商机跟进周报数据采集失败: {}", e.getMessage());
            return List.of();
        }
        if (followUps.isEmpty()) return List.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (SalesOpportunityFollowUp item : followUps) {
            if (item.getOpportunityId() != null) ids.add(item.getOpportunityId());
        }
        Map<Long, SalesOpportunity> opportunities = ids.isEmpty()
                ? Map.of()
                : salesOpportunityMapper.selectBatchIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(SalesOpportunity::getId, item -> item, (a, b) -> a));
        return followUps.stream().map(item -> {
            SalesOpportunity opportunity = opportunities.get(item.getOpportunityId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("opportunityId", item.getOpportunityId());
            row.put("opportunityName", opportunity != null ? opportunity.getName() : "");
            row.put("customer", opportunity != null ? opportunity.getCustomer() : "");
            row.put("owner", opportunity != null ? opportunity.getOwner() : "");
            row.put("follower", item.getFollower());
            row.put("status", item.getStatus());
            row.put("probability", item.getProbability());
            row.put("content", clip(item.getContent(), 200));
            row.put("nextFollowUp", clip(item.getNextFollowUp(), 120));
            row.put("followUpAt", item.getFollowUpAt() != null ? item.getFollowUpAt().toString() : null);
            return row;
        }).toList();
    }

    private Map<String, Object> collectFinance(LocalDate weekStart) {
        String currentMonth = weekStart.format(MONTH_FMT);
        LocalDate monthStart = weekStart.withDayOfMonth(1);
        LocalDate today = LocalDate.now();
        Map<String, Object> finance = new LinkedHashMap<>();
        finance.put("month", currentMonth);
        finance.put("newContractAmount", sumReceivable(new LambdaQueryWrapper<RevenueContractEntry>()
                .eq(RevenueContractEntry::getSaleMonth, currentMonth)));
        finance.put("deliveredAmount", sumReceivable(new LambdaQueryWrapper<RevenueContractEntry>()
                .isNotNull(RevenueContractEntry::getDeliveryDate)
                .ge(RevenueContractEntry::getDeliveryDate, monthStart)
                .le(RevenueContractEntry::getDeliveryDate, today)));
        finance.put("cumulativeReceivable", sumReceivable(new LambdaQueryWrapper<RevenueContractEntry>()
                .le(RevenueContractEntry::getSaleMonth, currentMonth)));
        return finance;
    }

    private BigDecimal sumReceivable(LambdaQueryWrapper<RevenueContractEntry> query) {
        List<RevenueContractEntry> entries = contractEntryMapper.selectList(
                query.select(RevenueContractEntry::getReceivableAmount));
        return entries.stream()
                .map(RevenueContractEntry::getReceivableAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Object> collectLastWeek(LocalDate weekStart) {
        Map<String, Object> lastWeek = new LinkedHashMap<>();
        WeeklyReport previous = reportMapper.selectOne(new LambdaQueryWrapper<WeeklyReport>()
                .eq(WeeklyReport::getWeekStartDate, weekStart.minusWeeks(1)));
        if (previous == null) {
            lastWeek.put("exists", false);
            return lastWeek;
        }
        lastWeek.put("exists", true);
        lastWeek.put("weekStart", previous.getWeekStartDate().toString());
        lastWeek.put("status", previous.getStatus());
        lastWeek.put("nextWeekPlan", previous.getNextWeekPlan());
        lastWeek.put("risks", previous.getRisks());
        return lastWeek;
    }

    // ==================== AI 生成 ====================

    /** 异步触发 AI 生成；若正在生成返回 null 由控制器返回 409。 */
    public WeeklyReport generateAsync(LocalDate weekStart) {
        WeeklyReport report = getOrCreate(weekStart);
        if (WeeklyReport.STATUS_GENERATING.equals(report.getStatus())) {
            return null;
        }
        report.setStatus(WeeklyReport.STATUS_GENERATING);
        report.setGenerationError(null);
        reportMapper.updateById(report);
        taskExecutor.execute(() -> generate(weekStart));
        return report;
    }

    /** 同步生成（供异步任务与测试调用）。 */
    public void generate(LocalDate weekStart) {
        WeeklyReport report = getOrCreate(weekStart);
        try {
            Map<String, Object> facts = collectFacts(weekStart);
            report.setAutoFactsJson(objectMapper.writeValueAsString(facts));
            String factsJson = objectMapper.writeValueAsString(distillFactsForGeneration(facts));

            if (digestLlmClient.candidateCount() == 0) {
                throw new IllegalStateException(
                        "摘要模型未配置或未启用，请在「模型管理」勾选「摘要使用」的模型并补全接口地址");
            }
            String systemKpi = buildKpiSection(weekStart);
            if (systemKpi == null) {
                log.info("周报 KPI 无当月营收数据，kpiSection 采用模型输出（生成指引/企微口径优先） weekStart={}", weekStart);
            }
            List<String> projectGroups = projectGroupWhitelist();
            String userInput = buildUserInput(report, factsJson, systemKpi, projectGroups);
            // 按「模型管理」排序的摘要模型依次尝试，首选额度/鉴权失败时自动换下一个
            DigestLlmClient.ChatResult chat =
                    digestLlmClient.chat(generationSystemPrompt(), userInput);
            validateResult(chat.json());
            chat = regenerateIfOffTemplate(userInput, chat, weekStart);
            JsonNode result = chat.json();

            report.setCoreWork(normalizeGroups(result.path("coreWork").asText(), projectGroups));
            report.setKpiSection(composeKpiSection(systemKpi, result.path("kpiSection").asText()));
            report.setRisks(normalizeGroups(result.path("risks").asText(), projectGroups));
            report.setNextWeekPlan(result.path("nextWeekPlan").asText());
            report.setMinutesMarkdown(result.path("minutesMarkdown").asText());
            report.setStatus(WeeklyReport.STATUS_DRAFT);
            report.setGenerationModel(chat.model());
            report.setGenerationProvider(chat.providerCode());
            report.setGenerationMode("AI");
            report.setGenerationError(null);
            reportMapper.updateById(report);
        } catch (Exception e) {
            log.error("周报 AI 生成失败 weekStart={}", weekStart, e);
            report.setStatus(WeeklyReport.STATUS_GENERATION_FAILED);
            String message = String.valueOf(e.getMessage());
            report.setGenerationError(message.length() > 500 ? message.substring(0, 500) : message);
            reportMapper.updateById(report);
            notifyFailure(weekStart, message);
        }
    }

    /**
     * 生成失败即时企微告警：周五 17:00 挂了不必等到第二天早上才被人发现。
     * 未配置接收人则跳过；告警失败不得覆盖原始错误。
     */
    private void notifyFailure(LocalDate weekStart, String message) {
        if (!StringUtils.hasText(notifyWeComUser)) {
            return;
        }
        try {
            String link = StringUtils.hasText(publicBaseUrl)
                    ? publicBaseUrl.replaceAll("/+$", "") + "/weekly-report"
                    : "/weekly-report";
            String detail = message == null ? "未知错误" : message;
            weComClient.pushText(notifyWeComUser,
                    "本周周报自动生成失败（周起始 " + weekStart + "）：\n"
                            + (detail.length() > 300 ? detail.substring(0, 300) : detail)
                            + "\n重新生成或手工补：" + link);
        } catch (Exception e) {
            log.warn("周报失败告警推送失败 weekStart={}: {}", weekStart, e.getMessage());
        }
    }

    /**
     * KPI 由业务线利润表计算，生成后覆盖模型输出。
     * 只保留启用且配置了 kpi_report_group 的业务线，口径与业务线利润页一致。
     * 当月营收未同步（系统没有可用数字）时返回 {@code null}，此时保留模型输出，
     * 让「生成指引」里写的 KPI 口径不被报错文案顶掉。
     */
    String buildKpiSection(LocalDate weekStart) {
        try {
            Set<Long> managed = businessLineMapper.selectList(
                            new LambdaQueryWrapper<BusinessLine>()
                                    .eq(BusinessLine::getStatus, 1)
                                    .isNotNull(BusinessLine::getKpiReportGroup))
                    .stream()
                    .map(BusinessLine::getId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            YearMonth month = YearMonth.from(weekStart);
            List<String> months = new ArrayList<>();
            for (int i = 0; i <= 3; i++) {
                months.add(month.minusMonths(i).toString());
            }
            List<BizLineProfitReport> rows = profitReportMapper.selectList(
                    new LambdaQueryWrapper<BizLineProfitReport>()
                            .in(BizLineProfitReport::getYearMonth, months));
            if (rows == null) {
                rows = List.of();
            }
            if (!managed.isEmpty()) {
                rows = rows.stream()
                        .filter(row -> row.getBusinessLineId() != null && managed.contains(row.getBusinessLineId()))
                        .toList();
            }
            return WeeklyReportKpiBuilder.render(weekStart, LocalDate.now(), rows);
        } catch (Exception e) {
            log.warn("周报 KPI 计算失败 weekStart={}: {}", weekStart, e.getMessage());
            return null;
        }
    }

    /**
     * kpiSection 由「KPI：」+「商机：」两段组成（见 docs/weekly-report-template.md）。
     * 系统 KPI 有数字时以其为准（数字必须准确），没有系统数字时用模型按生成指引写的口径；
     * 商机始终用模型输出（它汇总了企微、人为补充、生成指引与自动事实里的 opportunities）。
     */
    static String composeKpiSection(String systemKpi, String modelSection) {
        String opportunities = blockAfter(modelSection, "商机");
        String kpi = StringUtils.hasText(systemKpi) ? systemKpi : blockAfter(modelSection, "KPI");
        if (!StringUtils.hasText(kpi) && !StringUtils.hasText(opportunities)) {
            // 模型没按模版输出，原样保留，避免把内容弄丢
            return modelSection;
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(kpi)) {
            sb.append("KPI：\n").append(kpi.trim());
        }
        if (StringUtils.hasText(opportunities)) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("商机：\n").append(opportunities.trim());
        }
        return sb.toString();
    }

    /** 取「标题：」行之后到下一个标题行之前的正文；没有该标题行返回 null。 */
    private static String blockAfter(String text, String label) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        boolean inside = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (isHeaderLine(line, label)) {
                inside = true;
                continue;
            }
            if (inside && isHeaderLine(line, null)) {
                break;
            }
            if (inside) {
                buf.append(line).append('\n');
            }
        }
        String out = buf.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /** 标题行：不以「- 」开头、以「：」结尾；给定 label 时要求标题名完全匹配。 */
    private static boolean isHeaderLine(String line, String label) {
        if (line.startsWith("- ")) {
            return false;
        }
        if (label != null) {
            return line.equals(label + "：") || line.equals(label + ":");
        }
        return line.endsWith("：") && line.length() <= 14;
    }

    /** 下周计划里不允许出现的具体日期：10/23、10月23日、2026-10-23。 */
    private static final Pattern PLAN_DATE = Pattern.compile(
            "\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日|\\d{1,2}\\s*/\\s*\\d{1,2}");

    /**
     * 生成后按固定模版自检（见 docs/weekly-report-template.md），返回不合规项；空列表表示合规。
     */
    static List<String> templateIssues(JsonNode result) {
        List<String> issues = new ArrayList<>();
        String coreWork = result.path("coreWork").asText("");
        if (!hasAnyHeader(coreWork, List.of("项目", "产品", "会员通"))) {
            issues.add("coreWork 缺少「项目：/产品：/会员通：」标题行");
        }
        if (hasAnyHeader(coreWork, List.of("商机"))) {
            issues.add("coreWork 不应再出现「商机：」，商机统一放到 kpiSection");
        }
        String kpi = result.path("kpiSection").asText("");
        if (!hasAnyHeader(kpi, List.of("KPI"))) {
            issues.add("kpiSection 缺少「KPI：」标题行");
        }
        if (!hasAnyHeader(kpi, List.of("商机"))) {
            issues.add("kpiSection 缺少「商机：」标题行");
        }
        if (!hasAnyHeader(result.path("risks").asText(""), List.of("项目", "会员通"))) {
            issues.add("risks 缺少「项目：/会员通：」标题行");
        }
        Matcher matcher = PLAN_DATE.matcher(result.path("nextWeekPlan").asText(""));
        if (matcher.find()) {
            issues.add("nextWeekPlan 不应出现具体日期：" + matcher.group());
        }
        return issues;
    }

    private static boolean hasAnyHeader(String text, List<String> labels) {
        return text.lines().map(String::trim)
                .anyMatch(line -> labels.stream().anyMatch(label -> isHeaderLine(line, label)));
    }

    /** 模版不合规时按不合规项补一轮提示重试；重试仍不合规则保留首次结果。 */
    private DigestLlmClient.ChatResult regenerateIfOffTemplate(String userInput,
                                                               DigestLlmClient.ChatResult chat,
                                                               LocalDate weekStart) {
        List<String> issues = templateIssues(chat.json());
        if (issues.isEmpty()) {
            return chat;
        }
        log.warn("周报输出不符合固定模版，重试一次 weekStart={} issues={}", weekStart, issues);
        try {
            DigestLlmClient.ChatResult retry = digestLlmClient.chat(generationSystemPrompt(),
                    userInput + "\n\n== 上次输出不符合固定模版，必须逐条修正 ==\n- "
                            + String.join("\n- ", issues) + "\n");
            validateResult(retry.json());
            List<String> remaining = templateIssues(retry.json());
            if (remaining.isEmpty()) {
                return retry;
            }
            log.warn("周报重试后仍不符合模版，保留首次结果 weekStart={} issues={}", weekStart, remaining);
        } catch (Exception e) {
            log.warn("周报模版重试失败，保留首次结果 weekStart={}: {}", weekStart, e.getMessage());
        }
        return chat;
    }

    /**
     * 「项目：」二级分类白名单：取全渠道云鹿（定制 / SAAS）业务线下的一级项目名，
     * 即可用项目主数据维护、不用改代码；也可用系统配置 weekly-report/core-work.project-groups 直接覆盖（逗号分隔）。
     */
    List<String> projectGroupWhitelist() {
        String override = configService.getValue(CONFIG_GROUP, "core-work.project-groups", null);
        if (StringUtils.hasText(override)) {
            List<String> configured = Arrays.stream(override.split("[,，、]"))
                    .map(String::trim).filter(StringUtils::hasText).distinct().toList();
            if (!configured.isEmpty()) {
                return configured;
            }
        }
        try {
            List<Long> lineIds = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                            .eq(BusinessLine::getStatus, 1)
                            .like(BusinessLine::getName, "云鹿"))
                    .stream().map(BusinessLine::getId).toList();
            if (lineIds.isEmpty()) {
                return DEFAULT_PROJECT_GROUPS;
            }
            List<String> names = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                            .in(Project::getBusinessLineId, lineIds)
                            .eq(Project::getLevel, 1))
                    .stream().map(Project::getName).filter(StringUtils::hasText).distinct().toList();
            return names.isEmpty() ? DEFAULT_PROJECT_GROUPS : names;
        } catch (Exception e) {
            log.warn("周报项目分组白名单读取失败，回退默认值: {}", e.getMessage());
            return DEFAULT_PROJECT_GROUPS;
        }
    }

    /**
     * 二级分类白名单校正（模版见 docs/weekly-report-template.md）：
     * 「项目：」段只保留白名单里的客户项目名，**白名单外的客户统一归到「其他项目」**（多个合并成一个标题）；
     * 「产品：」段只保留 全渠道产品 / 海外产品；「会员通：」段不允许子分类。
     * 被改名的分组其下条目全部保留，不丢内容。
     */
    static String normalizeGroups(String text, List<String> projectGroups) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        String section = "";
        boolean othersEmitted = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
            String level1 = fixedLevel1Name(line);
            if (level1 != null) {
                section = level1;
                othersEmitted = false;
                out.append(raw.stripTrailing()).append('\n');
                continue;
            }
            String group = indentedGroupName(raw);
            if (group != null) {
                if ("项目".equals(section)) {
                    if (projectGroups.contains(group)) {
                        out.append(raw.stripTrailing()).append('\n');
                    } else if (OTHERS_GROUP.equals(group)) {
                        if (!othersEmitted) {
                            othersEmitted = true;
                            out.append("  ").append(OTHERS_GROUP).append("：\n");
                        }
                    } else if (!othersEmitted) {
                        // 系统里没有的客户：第一个改名成「其他项目」，其余合并进去
                        othersEmitted = true;
                        out.append("  ").append(OTHERS_GROUP).append("：\n");
                    }
                    continue;
                }
                if ("产品".equals(section)) {
                    if (PRODUCT_GROUPS.contains(group)) {
                        out.append(raw.stripTrailing()).append('\n');
                    }
                    continue;
                }
                // 会员通 / KPI / 商机：不分子集，子分类标题行删掉，条目保留
                continue;
            }
            out.append(raw.stripTrailing()).append('\n');
        }
        return out.toString().strip();
    }

    private static String fixedLevel1Name(String line) {
        for (String name : LEVEL1_GROUPS) {
            if (line.equals(name + "：") || line.equals(name + ":")) {
                return name;
            }
        }
        return null;
    }

    /** 二级分类行：缩进至少两个空格、以「：」结尾、不是「- 」条目；返回去掉冒号的名称。 */
    private static String indentedGroupName(String raw) {
        String line = raw.strip();
        boolean colonEnded = line.endsWith("：") || line.endsWith(":");
        if (line.isEmpty() || line.startsWith("-") || !colonEnded) {
            return null;
        }
        if (raw.length() - raw.stripLeading().length() < 2) {
            return null;
        }
        String name = line.substring(0, line.length() - 1).strip();
        return name.isEmpty() || name.length() > 20 ? null : name;
    }

    /**
     * 拼装模型输入，顺序即权重：自动事实 → 系统 KPI（有则原样沿用）→ 项目分组白名单 → 企微总结 → 人为补充 → 生成指引（最后，权重最高）。
     */
    String buildUserInput(WeeklyReport report, String factsJson, String systemKpi, List<String> projectGroups) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 自动采集事实 ==\n").append(factsJson).append("\n\n");
        if (StringUtils.hasText(systemKpi)) {
            sb.append("== 系统 KPI（有本段时必须原样写入 kpiSection，数字不得改写） ==\n")
                    .append(systemKpi).append("\n\n");
        }
        if (projectGroups != null && !projectGroups.isEmpty()) {
            sb.append("== 项目分组白名单（「项目：」的二级分类只能取下面的项目名，排在最后的「其他项目」用于收纳白名单外的客户，不要自建其它分组名） ==\n")
                    .append(String.join("、", projectGroups)).append("、").append(OTHERS_GROUP).append("\n\n");
        }
        if (StringUtils.hasText(report.getWecomSummary())) {
            sb.append("== 企微智能总结（必用，不得忽略） ==\n")
                    .append(report.getWecomSummary()).append("\n\n");
        }
        if (StringUtils.hasText(report.getManualNotes())) {
            sb.append("== 人为补充信息（必用，不得忽略） ==\n")
                    .append(report.getManualNotes()).append("\n\n");
        }
        if (StringUtils.hasText(report.getGenerationPrompt())) {
            sb.append("== 生成指引（用户提示词，优先遵循其侧重点要求） ==\n")
                    .append(report.getGenerationPrompt()).append("\n\n");
        }
        return sb.toString();
    }

    private void validateResult(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw new IllegalStateException("AI 返回格式错误");
        }
        for (String key : List.of("coreWork", "kpiSection", "risks", "nextWeekPlan", "minutesMarkdown")) {
            if (!result.path(key).isTextual() || result.path(key).asText().isBlank()) {
                throw new IllegalStateException("AI 返回缺少字段: " + key);
            }
        }
        if (!result.path("minutesMarkdown").asText().trim().startsWith(MINUTES_TITLE)) {
            throw new IllegalStateException("AI 周会纪要缺少固定标题");
        }
    }

    /** 生成前提炼：大事儿 highlights 最多 12 条，本周商机跟进最多 8 条。 */
    public Map<String, Object> distillFactsForGeneration(Map<String, Object> facts) {
        Map<String, Object> distilled = new LinkedHashMap<>(facts);
        Object raw = facts.get("keyMatters");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> ranked = list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) item;
                        return row;
                    })
                    .filter(this::isHighlightMatter)
                    .sorted((a, b) -> Integer.compare(highlightScore(b), highlightScore(a)))
                    .limit(12)
                    .toList();
            distilled.put("keyMatters", ranked);
            distilled.put("omittedMatterCount", Math.max(0, list.size() - ranked.size()));
        }
        Object opportunityRaw = facts.get("opportunities");
        if (opportunityRaw instanceof List<?> opportunityList) {
            List<Map<String, Object>> rankedOpps = opportunityList.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) item;
                        return row;
                    })
                    .filter(this::isHighlightOpportunity)
                    .limit(8)
                    .toList();
            distilled.put("opportunities", rankedOpps);
            distilled.put("omittedOpportunityCount", Math.max(0, opportunityList.size() - rankedOpps.size()));
        }
        distilled.put("instruction", "自动事实只保留重点。企微智能总结和人为补充不受 omitted 计数限制，必须采纳");
        return distilled;
    }

    private boolean isHighlightOpportunity(Map<String, Object> row) {
        String status = str(row.get("status"));
        String content = str(row.get("content"));
        if (status.contains("成交") || status.contains("流失") || status.contains("谈判") || status.contains("报价")) {
            return true;
        }
        return StringUtils.hasText(content) && !isRoutine(content);
    }

    private boolean isHighlightMatter(Map<String, Object> matter) {
        String status = str(matter.get("status"));
        String issues = str(matter.get("issues"));
        String support = str(matter.get("supportNeeded"));
        String summary = str(matter.get("progressSummary"));
        boolean currentWeek = Boolean.TRUE.equals(matter.get("currentWeek"));
        if (status.contains("风险") || status.contains("阻塞") || status.contains("完成")) return true;
        if (StringUtils.hasText(issues) || StringUtils.hasText(support)) return true;
        if (currentWeek && StringUtils.hasText(summary) && !isRoutine(summary)) return true;
        String priority = str(matter.get("priority")).toUpperCase();
        return currentWeek && (priority.contains("P0") || priority.contains("高"));
    }

    private int highlightScore(Map<String, Object> matter) {
        int score = 0;
        String status = str(matter.get("status"));
        if (status.contains("阻塞")) score += 8;
        if (status.contains("风险")) score += 6;
        if (status.contains("完成")) score += 4;
        if (Boolean.TRUE.equals(matter.get("currentWeek"))) score += 3;
        String priority = str(matter.get("priority")).toUpperCase();
        if (priority.contains("P0") || priority.contains("高")) score += 3;
        if (priority.contains("P1")) score += 1;
        if (StringUtils.hasText(str(matter.get("issues")))) score += 2;
        return score;
    }

    private boolean isRoutine(String text) {
        return text.contains("按原计划") || text.contains("持续推进") || text.contains("正常推进")
                || text.contains("无变化") || text.contains("本周继续");
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String clip(String value, int max) {
        if (!StringUtils.hasText(value)) return value;
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    /** 生成提示词：固定模版（见 docs/weekly-report-template.md），生成指引优先级最高，KPI 数字由系统覆盖。 */
    public String generationSystemPrompt() {
        return "你是陆泽科技电商业务BU负责人的周报秘书。给管理层看，保留本周有信息量的进展，不要写成只有几条口号的简报。\n"
                + "\n== 材料 ==\n"
                + "1. 生成指引是用户对本次生成的硬性要求，优先级最高：侧重点、取舍口径、排除项都要逐条落实；与本提示词其它条目冲突时以生成指引为准。\n"
                + "2. 企微智能总结、人为补充信息是必用材料。里面的进展、结论、风险、待决策和下周动作都要写进周报或纪要，不得因为自动事实做了省略就丢掉。\n"
                + "3. 自动采集事实补充大事儿和商机。opportunities 必须纳入 kpiSection 的商机段。\n"
                + "4. 不写纯寒暄、排班、无变化的日常项。omitted 计数只约束自动事实，不约束企微和人为补充。\n"
                + "\n== 周报排版（四段纯文本，禁止 Markdown） ==\n"
                + "一级标题顶格并以「：」结尾；二级分类缩进两个空格并以「：」结尾；条目以「- 」开头，一行一条。\n"
                + "某组本周无事项时，整组（含标题行）不要出现。\n"
                + "\n【coreWork 本周核心工作完成情况】固定三个一级标题，顺序如下：\n"
                + "项目：按客户交付项目做二级分类，只能取材料里「项目分组白名单」给出的项目名（如 皇家项目 / 飞鹤 / 佳贝艾特 / 黄天鹅 / Speedo / 澳优 / 逢时），每个子分类 1-3 条；不属于白名单客户的进展（系统里没建项目的客户）一律收进「其他项目」子分类并排在最后，不要自建其它分组名。\n"
                + "产品：只允许两个二级分类，名称必须原样照抄：全渠道产品、海外产品。云鹿全渠道产品/CDP/MA/BI/AI 的事项一律归「全渠道产品」，海外产品研发的事项归「海外产品」；每个子分类 1-4 条。\n"
                + "会员通：不分子集，直接平铺条目。\n"
                + "coreWork 全篇最多 12 条，每条不超过 80 字，格式「- 事项：结果或卡点。下一步：动作」；本段不要再出现「商机：」。\n"
                + "\n【kpiSection KPI相关情况】固定两个一级标题，两个都要出现：\n"
                + "KPI：材料里有「系统 KPI」段落时，这一行下面写「系统计算」（系统会用业务线利润原文替换）；没有「系统 KPI」段落时，按生成指引和企微总结的口径写 KPI（可写预估），不要写「系统计算」，也不要声称没有数据。\n"
                + "商机：opportunities 全部放这里，一行一条「- 客户或商机：阶段变化/金额」。\n"
                + "\n【risks 问题/风险与解决办法】固定两个一级标题，无事项的整组不出现：\n"
                + "项目：与 coreWork 相同的客户项目做二级分类；会员通：不分子集直接平铺。\n"
                + "每条「- 风险：影响。解决办法：动作」，最多 5 条。\n"
                + "\n【nextWeekPlan 下周工作计划】只写条目「- 工作项」，最多 6 条。\n"
                + "不要出现任何具体日期（如 10/23、11月13日、2026-10-23），需要表达时间就用「下周」「月底前」「双十一后」。\n"
                + "\n== 纪要 ==\n"
                + "首行：# 电商业务BU周会会议纪要\n"
                + "次行：**会议周期：** YYYY年MM月DD日 - YYYY年MM月DD日\n"
                + "按项目/产品分组，覆盖与周报相同的重点，无内容不建章节。每组最多 4 条，每条不超过 80 字。\n"
                + "末尾 ## 下周重点工作计划，HTML <table> 三列：序号、工作项、预计时间，最多 6 行。\n"
                + "纪要正文不超过 1500 字。\n"
                + "\n== 输出 ==\n"
                + "严格 JSON，五个字段的字符串里都必须带上述标题行，换行用 \\n 转义：\n"
                + "{\"coreWork\":\"项目：…\\n  项目名：\\n  - 事项：…\","
                + "\"kpiSection\":\"KPI：…\\n\\n商机：…\","
                + "\"risks\":\"项目：…\\n  项目名：\\n  - 风险：…\","
                + "\"nextWeekPlan\":\"- 工作项\","
                + "\"minutesMarkdown\":\"# 电商业务BU周会会议纪要\\n…\"}";
    }

    // ==================== 发布：语雀 ====================

    /** 发布周会纪要至语雀：创建文档 + 挂载 部门会议/YYYY + TOC 校验。幂等。 */
    public WeeklyReport publishYuque(Long reportId, Long userId) {
        WeeklyReport report = getById(reportId);
        if (!StringUtils.hasText(report.getMinutesMarkdown())) {
            throw new IllegalStateException("周会纪要为空，无法发布");
        }
        if ("VERIFIED".equals(report.getYuqueTocStatus()) && StringUtils.hasText(report.getYuqueDocUrl())) {
            return report;
        }
        String repo = yuqueConfig("repo", "vuntcs/cf_records");
        String parentDir = yuqueConfig("parentDir", "部门会议");
        String baseUrl = yuqueBaseUrl();
        String year = String.valueOf(report.getWeekStartDate().getYear());

        // 1. 创建文档（若尚未创建）
        if (report.getYuqueDocId() == null) {
            String title = buildMinutesTitle(report);
            Map<String, Object> doc = yuqueClient.createDoc(repo, title, report.getMinutesMarkdown(),
                    "markdown", 1);
            Object id = doc.get("id");
            report.setYuqueDocId(id instanceof Number ? ((Number) id).intValue()
                    : Integer.parseInt(String.valueOf(id)));
            report.setYuqueDocSlug(String.valueOf(doc.getOrDefault("slug", "")));
            String url = String.valueOf(doc.getOrDefault("url", ""));
            report.setYuqueDocUrl(buildDocUrl(baseUrl, repo, report.getYuqueDocSlug(), url));
            reportMapper.updateById(report);
        }

        // 2. TOC：找到 部门会议/YYYY 节点，将文档节点挂载其下
        try {
            JsonNode toc = yuqueClient.getToc(repo);
            String docNodeUuid = findTocNodeUuid(toc, report.getYuqueDocId());
            String yearNodeUuid = findYearNodeUuid(toc, parentDir, year);
            if (docNodeUuid == null) {
                report.setYuqueTocStatus("NOT_FOUND");
            } else if (yearNodeUuid == null) {
                report.setYuqueTocStatus("MOVED");
            } else {
                ObjectNode tocData = objectMapper.createObjectNode();
                tocData.put("action", "appendNode");
                tocData.put("action_mode", "child");
                tocData.put("target_uuid", yearNodeUuid);
                tocData.put("node_uuid", docNodeUuid);
                yuqueClient.updateToc(repo, toJson(tocData));
                report.setYuqueTocStatus("VERIFIED");
            }
        } catch (IllegalStateException e) {
            // MCP 不可用（403）：文档已在根目录创建，TOC 人工处理
            report.setYuqueTocStatus("MOVED");
            report.setGenerationError(truncate("TOC 挂载失败：" + e.getMessage()));
        }
        if (!StringUtils.hasText(report.getYuqueDocUrl())) {
            report.setYuqueDocUrl(buildDocUrl(baseUrl, repo, report.getYuqueDocSlug(), ""));
        }
        report.setStatus(WeeklyReport.STATUS_PUBLISHED);
        report.setPublishedBy(userId);
        reportMapper.updateById(report);
        return report;
    }

    private String buildMinutesTitle(WeeklyReport report) {
        LocalDate start = report.getWeekStartDate();
        LocalDate end = report.getPeriodEndDate() != null ? report.getPeriodEndDate() : start.plusDays(4);
        return String.format("电商业务BU周会会议纪要（%d年%02d月%02d日-%02d月%02d日）",
                start.getYear(), start.getMonthValue(), start.getDayOfMonth(),
                end.getMonthValue(), end.getDayOfMonth());
    }

    private String buildDocUrl(String baseUrl, String repo, String slug, String fallback) {
        if (StringUtils.hasText(slug)) {
            return baseUrl.replaceAll("/+$", "") + "/" + repo + "/" + slug;
        }
        return fallback;
    }

    /** 在 TOC 树中查找指定 doc_id 的节点 uuid。 */
    private String findTocNodeUuid(JsonNode toc, Integer docId) {
        if (docId == null || toc == null) return null;
        return findUuid(toc, node -> node.path("doc_id").asInt(-1) == docId
                || node.path("docId").asInt(-1) == docId);
    }

    /** 在 TOC 树中查找 父目录/YYYY 节点（优先匹配 父目录 下的年份子节点）。 */
    private String findYearNodeUuid(JsonNode toc, String parentDir, String year) {
        String parentUuid = findUuid(toc, node -> parentDir.equals(node.path("title").asText("")));
        if (parentUuid == null) {
            return findUuid(toc, node -> year.equals(node.path("title").asText("")));
        }
        JsonNode parent = findNodeByUuid(toc, parentUuid);
        if (parent != null) {
            String childUuid = findUuid(parent, node -> year.equals(node.path("title").asText("")));
            if (childUuid != null) return childUuid;
        }
        return findUuid(toc, node -> year.equals(node.path("title").asText("")));
    }

    private String findUuid(JsonNode node, java.util.function.Predicate<JsonNode> match) {
        JsonNode found = findNode(node, match);
        return found == null ? null : found.path("uuid").asText(null);
    }

    private JsonNode findNodeByUuid(JsonNode node, String uuid) {
        return findNode(node, n -> uuid.equals(n.path("uuid").asText("")));
    }

    private JsonNode findNode(JsonNode node, java.util.function.Predicate<JsonNode> match) {
        if (node == null) return null;
        if (node.isObject()) {
            if (match.test(node)) return node;
            for (JsonNode child : iterableChildren(node)) {
                JsonNode found = findNode(child, match);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findNode(item, match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Iterable<JsonNode> iterableChildren(JsonNode node) {
        java.util.List<JsonNode> children = new java.util.ArrayList<>();
        for (String key : List.of("children", "child_nodes", "nodes", "items", "toc")) {
            JsonNode child = node.path(key);
            if (child.isArray()) {
                child.forEach(children::add);
            }
        }
        return children;
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    // ==================== 汇总表回填（MANUAL） ====================

    /** 目标行信息（供人工回填）。 */
    public Map<String, Object> sheetTargetInfo(Long reportId) {
        WeeklyReport report = getById(reportId);
        Map<String, Object> info = new LinkedHashMap<>();
        LocalDate start = report.getWeekStartDate();
        LocalDate end = report.getPeriodEndDate() != null ? report.getPeriodEndDate() : start.plusDays(4);
        info.put("dateRangeLabel", String.format("%02d.%02d-%02d.%02d",
                start.getMonthValue(), start.getDayOfMonth(),
                end.getMonthValue(), end.getDayOfMonth()));
        info.put("teamName", configService.getValue(CONFIG_GROUP, "sheet.team-name", "电商业务BU"));
        info.put("sheetName", configService.getValue(CONFIG_GROUP, "sheet.sheet-name", "电商业务"));
        String slug = configService.getValue(CONFIG_GROUP, "sheet.doc-slug", "staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        String baseUrl = yuqueBaseUrl();
        info.put("sheetUrl", baseUrl.replaceAll("/+$", "") + "/" + slug);
        info.put("minutesUrl", report.getYuqueDocUrl());
        return info;
    }

    /** MANUAL：确认人工已粘贴纪要链接；API：尝试写入语雀表格后再标记。 */
    public WeeklyReport fillSheet(Long reportId) {
        WeeklyReport report = getById(reportId);
        if (!StringUtils.hasText(report.getYuqueDocUrl())) {
            throw new IllegalStateException("请先发布语雀纪要，再回填汇总表");
        }
        String mode = configService.getValue(CONFIG_GROUP, "sheet.mode", "MANUAL");
        if (!"API".equalsIgnoreCase(mode)) {
            report.setSheetSyncStatus("MANUAL_DONE");
            report.setSheetSyncedAt(LocalDateTime.now());
            reportMapper.updateById(report);
            return report;
        }
        LocalDate start = report.getWeekStartDate();
        LocalDate end = report.getPeriodEndDate() != null ? report.getPeriodEndDate() : start.plusDays(4);
        String dateRange = String.format("%02d.%02d-%02d.%02d",
                start.getMonthValue(), start.getDayOfMonth(),
                end.getMonthValue(), end.getDayOfMonth());
        String teamName = configService.getValue(CONFIG_GROUP, "sheet.team-name", "电商业务BU");
        String sheetName = configService.getValue(CONFIG_GROUP, "sheet.sheet-name", "电商业务");
        String slug = configService.getValue(CONFIG_GROUP, "sheet.doc-slug", "staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        try {
            yuqueClient.writeSheetRow(
                    mode, null, null, slug, sheetName, dateRange, teamName, report.getYuqueDocUrl());
            report.setSheetSyncStatus("MANUAL_DONE");
            report.setSheetSyncedAt(LocalDateTime.now());
            reportMapper.updateById(report);
            return report;
        } catch (RuntimeException e) {
            report.setSheetSyncStatus("API_FAILED");
            reportMapper.updateById(report);
            throw e;
        }
    }

    // ==================== 企微推送 ====================

    /** 推送企微提醒（终稿内容 + 一键复制提示）。 */
    public WeeklyReport pushWecom(Long reportId) {
        WeeklyReport report = getById(reportId);
        if (!hasContent(report)) {
            throw new IllegalStateException("周报内容为空，无法推送");
        }
        String user = configService.getValue(CONFIG_GROUP, "report.notify-wecom-user", null);
        if (!StringUtils.hasText(user)) {
            throw new IllegalStateException("未配置企微提醒用户（weekly-report / report.notify-wecom-user）");
        }
        String content = buildWecomMessage(report);
        weComClient.pushText(user, content);
        report.setWecomPushStatus("SUCCESS");
        report.setWecomPushedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        return report;
    }

    private String buildWecomMessage(WeeklyReport report) {
        StringBuilder sb = new StringBuilder();
        LocalDate start = report.getWeekStartDate();
        sb.append("【BG周报】").append(start).append(" 周周报已就绪，请查收并复制提交企微汇报。\n\n");
        appendSection(sb, "本周核心工作完成情况", report.getCoreWork());
        appendSection(sb, "KPI相关情况", report.getKpiSection());
        appendSection(sb, "问题/风险与解决办法", report.getRisks());
        appendSection(sb, "下周工作计划", report.getNextWeekPlan());
        if (StringUtils.hasText(report.getYuqueDocUrl())) {
            sb.append("\n周会纪要：").append(report.getYuqueDocUrl());
        }
        String message = sb.toString();
        // 企微文本消息长度限制 2048 字节，保守截断
        return message.length() > 1800 ? message.substring(0, 1800) + "…（完整内容见系统）" : message;
    }

    private void appendSection(StringBuilder sb, String title, String body) {
        if (StringUtils.hasText(body)) {
            sb.append("■ ").append(title).append("\n").append(body.trim()).append("\n\n");
        }
    }

    private boolean hasContent(WeeklyReport report) {
        return StringUtils.hasText(report.getCoreWork())
                || StringUtils.hasText(report.getKpiSection())
                || StringUtils.hasText(report.getRisks())
                || StringUtils.hasText(report.getNextWeekPlan());
    }

    private void validateWeekStart(LocalDate weekStart) {
        if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 必须是周一");
        }
    }

    /** 语雀参数取自连接器（code=yuque）的扩展参数，页面入口在「连接器管理」。 */
    private String yuqueConfig(String key, String fallback) {
        return registryService.findByCode(ConnectorRegistryService.CODE_YUQUE)
                .map(connector -> registryService.extra(connector, key, fallback))
                .orElse(fallback);
    }

    private String yuqueBaseUrl() {
        return registryService.findByCode(ConnectorRegistryService.CODE_YUQUE)
                .map(Connector::getBaseUrl)
                .filter(StringUtils::hasText)
                .orElse("https://lucidata.yuque.com");
    }
}
