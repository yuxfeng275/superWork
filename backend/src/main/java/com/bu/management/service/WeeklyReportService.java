package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.integration.YuqueMcpClient;
import com.bu.management.mapper.RevenueContractEntryMapper;
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
import java.util.List;
import java.util.Map;
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
    private final SystemConfigService configService;
    private final EmailIntegrationConfigService integrationConfigService;
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

    /** 采集自动事实：大事儿进度 + 财务 + 上周闭环。 */
    public Map<String, Object> collectFacts(LocalDate weekStart) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("weekStart", weekStart.toString());
        facts.put("periodEnd", weekStart.plusDays(4).toString());
        facts.put("keyMatters", collectKeyMatters(weekStart));
        facts.put("finance", collectFinance(weekStart));
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
            BuKeyMatterWeeklyUpdateView update = matter.getCurrentWeekUpdate() != null
                    ? matter.getCurrentWeekUpdate() : matter.getLatestUpdate();
            if (update != null) {
                row.put("progressSummary", update.getProgressSummary());
                row.put("issues", update.getIssues());
                row.put("nextWeekPlan", update.getNextWeekPlan());
                row.put("supportNeeded", update.getSupportNeeded());
            }
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
            String factsJson = objectMapper.writeValueAsString(facts);
            report.setAutoFactsJson(factsJson);

            EmailIntegrationRuntimeConfig config = integrationConfigService.getRuntimeConfig();
            if (!config.isDeepSeekConfigured()) {
                throw new IllegalStateException("DeepSeek 未配置");
            }
            String userInput = buildUserInput(report, factsJson);
            JsonNode result = deepSeekClient.chatCompletion(config, systemPrompt(), userInput);
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

    private String systemPrompt() {
        return "你是陆泽科技电商业务BU的周报与周会纪要撰写助手。基于以下事实，按规则生成两份输出 (JSON)。\n"
                + "\n== 周报规则 ==\n"
                + "1. 四段固定标题：本周核心工作完成情况(按优先级)、KPI相关情况(财务、业务、提效、品质等)、问题/风险与解决办法、下周工作计划(含时间节点及预期结果)。\n"
                + "2. 第一段拆分为\"项目\"和\"产品\"两个子块；项目优先皇家项目、标品客户交付；产品优先全渠道云鹿、AI产品。\n"
                + "3. KPI 顺序：财务 → 业务 → 提效 → 品质；没有事实的维度可省略，财务不可省略。\n"
                + "4. 财务必须给出：本月新增合同金额（合同日期口径）、本月交付口径金额；有具体万元/元数值。\n"
                + "5. 风险和问题不超过3条，每条写清事项、影响和解决路径。\n"
                + "6. 下周计划3-4条，含明确日期和可验收结果。\n"
                + "7. 只使用提供的事实；不臆造。宁可留白不凑字。\n"
                + "8. 删除内部技术细节、个人排班、无状态变化的持续事项、不适合BG展示的敏感信息。\n"
                + "9. 周报四段（coreWork/kpiSection/risks/nextWeekPlan）必须是纯文本：禁止使用 Markdown 语法"
                + "（不要用 **、#、表格）；分项用「- 」开头，编号用「1. 」，子块标题单独一行（如\"项目：\"）。\n"
                + "\n== 周会纪要规则 ==\n"
                + "1. 文档第一行固定为一级标题 \"# 电商业务BU周会会议纪要\"。\n"
                + "2. 下一行：**会议周期：** YYYY年MM月DD日 - YYYY年MM月DD日（取本周周一和周五）。\n"
                + "3. 按项目/产品线分组（皇家宠物 → 全渠道云鹿 → 千人千面 → Oversea中台 → 飞鹤 → 逢时 → Speedo → 黄天鹅 → 短信渠道 → CDP系统优化 → 产品能力 → 团队交接 → 其他工作），本周无内容的线不留空章节。\n"
                + "4. 每个事项：已完成的动作与结果 + 当前进度/风险/依赖 + 下一步动作/负责人/日期。\n"
                + "5. 不写\"顺利推进\"\"持续赋能\"等无信息量表达。\n"
                + "6. 保留精确产品名、平台名、百分比、日期及错误现象。\n"
                + "7. 末尾：\"## 下周重点工作计划\"，用 HTML <table> 列出序号、工作项、预计时间。\n"
                + "\n== 输出 ==\n"
                + "严格的 JSON：\n"
                + "{\n"
                + "  \"coreWork\": \"...(项目/产品分块，纯文本)...\",\n"
                + "  \"kpiSection\": \"...(纯文本)...\",\n"
                + "  \"risks\": \"...(纯文本)...\",\n"
                + "  \"nextWeekPlan\": \"...(纯文本)...\",\n"
                + "  \"minutesMarkdown\": \"...(# 电商业务BU周会会议纪要，Markdown)...\"\n"
                + "}";
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
        String repo = configService.getValue(CONFIG_GROUP, "yuque.repo", "vuntcs/cf_records");
        String parentDir = configService.getValue(CONFIG_GROUP, "yuque.parent-dir", "部门会议");
        String baseUrl = configService.getValue(CONFIG_GROUP, "yuque.base-url", "https://lucidata.yuque.com");
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
        String baseUrl = configService.getValue(CONFIG_GROUP, "yuque.base-url", "https://lucidata.yuque.com");
        info.put("sheetUrl", baseUrl.replaceAll("/+$", "") + "/" + slug);
        info.put("minutesUrl", report.getYuqueDocUrl());
        return info;
    }

    /** 人工确认汇总表已回填。 */
    public WeeklyReport markSheetSynced(Long reportId) {
        WeeklyReport report = getById(reportId);
        report.setSheetSyncStatus("MANUAL_DONE");
        report.setSheetSyncedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        return report;
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
}
