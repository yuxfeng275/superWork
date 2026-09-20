package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.Connector;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.integration.YuqueMcpClient;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

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

    private final WeeklyReportMapper reportMapper;
    private final DeepSeekDigestClient deepSeekClient;
    private final YuqueMcpClient yuqueClient;
    private final WeComClient weComClient;
    private final BuKeyMatterService keyMatterService;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final SalesOpportunityFollowUpMapper opportunityFollowUpMapper;
    private final SalesOpportunityMapper salesOpportunityMapper;
    private final SystemConfigService configService;
    private final EmailIntegrationConfigService integrationConfigService;
    private final ConnectorRegistryService registryService;
    private final ObjectMapper objectMapper;

    @Resource(name = "emailTaskExecutor")
    private Executor taskExecutor;

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

    /** 保存人工输入（企微智能总结 + 补充信息）。 */
    public WeeklyReport saveInputs(Long reportId, String wecomSummary, String manualNotes) {
        WeeklyReport report = getById(reportId);
        if (wecomSummary != null) {
            report.setWecomSummary(wecomSummary);
        }
        if (manualNotes != null) {
            report.setManualNotes(manualNotes);
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
                row.put("progressSummary", clip(update.getProgressSummary(), 80));
                row.put("issues", clip(update.getIssues(), 60));
                row.put("nextWeekPlan", clip(update.getNextWeekPlan(), 60));
                row.put("supportNeeded", clip(update.getSupportNeeded(), 40));
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
            row.put("content", clip(item.getContent(), 80));
            row.put("nextFollowUp", clip(item.getNextFollowUp(), 40));
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

            EmailIntegrationRuntimeConfig config = integrationConfigService.getRuntimeConfig();
            if (!config.isDeepSeekConfigured()) {
                throw new IllegalStateException("DeepSeek 未配置");
            }
            String userInput = buildUserInput(report, factsJson);
            JsonNode result = deepSeekClient.chatCompletion(config, generationSystemPrompt(), userInput);
            validateResult(result);

            report.setCoreWork(result.path("coreWork").asText());
            report.setKpiSection(result.path("kpiSection").asText());
            report.setRisks(result.path("risks").asText());
            report.setNextWeekPlan(result.path("nextWeekPlan").asText());
            report.setMinutesMarkdown(result.path("minutesMarkdown").asText());
            report.setStatus(WeeklyReport.STATUS_DRAFT);
            report.setGenerationModel(config.deepSeekModel());
            report.setGenerationMode("AI");
            report.setGenerationError(null);
            reportMapper.updateById(report);
        } catch (Exception e) {
            log.error("周报 AI 生成失败 weekStart={}", weekStart, e);
            report.setStatus(WeeklyReport.STATUS_GENERATION_FAILED);
            String message = String.valueOf(e.getMessage());
            report.setGenerationError(message.length() > 500 ? message.substring(0, 500) : message);
            reportMapper.updateById(report);
        }
    }

    private String buildUserInput(WeeklyReport report, String factsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 自动采集事实 ==\n").append(factsJson).append("\n\n");
        if (StringUtils.hasText(report.getWecomSummary())) {
            sb.append("== 企微智能总结 ==\n").append(report.getWecomSummary()).append("\n\n");
        }
        if (StringUtils.hasText(report.getManualNotes())) {
            sb.append("== 人为补充信息 ==\n").append(report.getManualNotes()).append("\n\n");
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

    /** 生成前提炼：大事儿 highlights + 本周商机跟进（最多 4 条）。 */
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
                    .limit(6)
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
                    .limit(4)
                    .toList();
            distilled.put("opportunities", rankedOpps);
            distilled.put("omittedOpportunityCount", Math.max(0, opportunityList.size() - rankedOpps.size()));
        }
        distilled.put("instruction", "只写 highlights，不要复述被省略的事项或商机");
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

    /** 生成提示词：只提炼管理层该看的重点，不是工作流水账。 */
    public String generationSystemPrompt() {
        return "你是陆泽科技电商业务BU负责人的周报秘书。给管理层看，不是工作流水账。只提炼本周真正变化的重点。\n"
                + "\n== 取舍 ==\n"
                + "1. 只写：本周有结果、有风险/阻塞、需协调、商机阶段变化、或下周必须拍板的事项。\n"
                + "2. 不写：日常推进、无状态变化、排班、内部技术细节、被省略的事项。\n"
                + "3. 事实里 omittedMatterCount / omittedOpportunityCount 表示已丢弃的日常项，禁止补回去。\n"
                + "4. opportunities 是本周商机跟进，必须纳入 coreWork 或风险/下周计划，不要只写大事儿。\n"
                + "\n== 周报 ==\n"
                + "四段纯文本，禁止 Markdown。coreWork 分两行标题：项目： / 产品：；有商机跟进时再加 商机：。\n"
                + "coreWork 全篇最多 5 条；项目优先皇家/标品，产品优先云鹿/AI，商机写客户+阶段变化。\n"
                + "每条一行：「- 事项：结果或卡点。下一步+日期」，不超过 28 字。\n"
                + "KPI 只写财务两个数：本月新增合同、本月交付口径（万元）；其它维度无数字就省略。\n"
                + "风险最多 2 条；下周计划最多 3 条。四段合计不超过 450 字。\n"
                + "\n== 纪要 ==\n"
                + "首行：# 电商业务BU周会会议纪要\n"
                + "次行：**会议周期：** YYYY年MM月DD日 - YYYY年MM月DD日\n"
                + "只保留与周报同一批重点，按项目/产品分组，无内容不建章节。每组最多 2 条，每条不超过 28 字。\n"
                + "末尾 ## 下周重点工作计划，HTML <table> 三列：序号、工作项、预计时间，最多 3 行。\n"
                + "纪要正文不超过 600 字。\n"
                + "\n== 输出 ==\n"
                + "严格 JSON：{\"coreWork\":\"...\",\"kpiSection\":\"...\",\"risks\":\"...\",\"nextWeekPlan\":\"...\",\"minutesMarkdown\":\"...\"}";
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
