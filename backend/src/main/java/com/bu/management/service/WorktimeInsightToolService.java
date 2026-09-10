package com.bu.management.service;

import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工时系统经营洞察工具（AI 助手）：合同/成本/营收利润 KPI/同步状态。
 * 数据全部来自本地已同步库（revenue_contract_entry / revenue_cost_entry /
 * kpi_weekly_snapshot / worktime_sync_log），零外呼。
 * 需 revenue:view 权限的工具在 execute 内校验。
 * 任何失败都转换为 isError 结果，绝不向侧车抛异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorktimeInsightToolService {

    private final RevenueContractEntryMapper contractMapper;
    private final RevenueCostEntryMapper costMapper;
    private final WorktimeSyncLogMapper syncLogMapper;
    private final KpiReportService kpiReportService;
    private final SysRoleService sysRoleService;
    private final ObjectMapper objectMapper;

    public static final java.util.Set<String> TOOL_NAMES = java.util.Set.of(
            "analyze_my_contracts", "analyze_cost_structure", "get_kpi_summary", "get_worktime_sync_status");

    public boolean handles(String toolName) {
        return TOOL_NAMES.contains(toolName);
    }

    public List<AiAgentToolDefinition> definitions() {
        return List.of(
                new AiAgentToolDefinition("analyze_my_contracts",
                        "查询合同应收与回款：按业务线汇总合同金额、回款进度，并列出未回款大额合同 Top 5",
                        objectSchema(Map.of(
                                "year", integerProperty("年份，默认当前年"),
                                "top", integerProperty("未回款合同条数，默认 5，最大 10")), null)),
                new AiAgentToolDefinition("analyze_cost_structure",
                        "查询某月成本结构：人力/直接/第三方成本按业务线分布与占比",
                        objectSchema(Map.of(
                                "month", stringProperty("月份 YYYY-MM，默认上个月")), null)),
                new AiAgentToolDefinition("get_kpi_summary",
                        "查询年度经营 KPI：各业务线营收/毛利目标达成率、周环比变化、偏差备注",
                        objectSchema(Map.of(
                                "year", integerProperty("年份，默认当前年"),
                                "group", stringProperty("业务线分组过滤，如 会员通/全渠道云鹿/全渠道精准，缺省全部")), null)),
                new AiAgentToolDefinition("get_worktime_sync_status",
                        "查询工时系统数据自动同步状态：最近合同/工时/成本同步时间与结果",
                        objectSchema(Map.of(), null)));
    }

    public AiAgentToolResult execute(Long userId, String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "analyze_my_contracts" -> analyzeContracts(userId, args);
                case "analyze_cost_structure" -> analyzeCostStructure(userId, args);
                case "get_kpi_summary" -> kpiSummary(userId, args);
                case "get_worktime_sync_status" -> syncStatus(userId);
                default -> new AiAgentToolResult("未知工具：" + toolName, true);
            };
        } catch (Exception e) {
            log.warn("工时经营洞察工具执行失败: tool={}, error={}", toolName, e.getMessage());
            return new AiAgentToolResult("工具执行失败：" + e.getMessage(), true);
        }
    }

    // ==================== 合同应收/回款 ====================

    private AiAgentToolResult analyzeContracts(Long userId, JsonNode args) {
        if (!hasRevenuePermission(userId)) {
            return new AiAgentToolResult("合同分析需要「营收查看」权限，当前账号无权访问", true);
        }
        int year = intArg(args, "year", YearMonth.now().getYear());
        List<RevenueContractEntry> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<RevenueContractEntry>()
                        .likeRight(RevenueContractEntry::getSaleMonth, String.valueOf(year))
                        .isNotNull(RevenueContractEntry::getReceivableAmount));
        if (contracts.isEmpty()) {
            return new AiAgentToolResult(year + " 年暂无已同步的合同明细数据", false);
        }
        Map<String, BigDecimal> receivableByLine = new LinkedHashMap<>();
        Map<String, BigDecimal> receivedByLine = new LinkedHashMap<>();
        BigDecimal totalReceivable = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        for (RevenueContractEntry c : contracts) {
            String line = c.getBizLineRaw() == null || c.getBizLineRaw().isBlank() ? "未分类" : c.getBizLineRaw();
            BigDecimal receivable = nvl(c.getReceivableAmount());
            BigDecimal received = nvl(c.getReceivedAmount());
            receivableByLine.merge(line, receivable, BigDecimal::add);
            receivedByLine.merge(line, received, BigDecimal::add);
            totalReceivable = totalReceivable.add(receivable);
            totalReceived = totalReceived.add(received);
        }
        StringBuilder sb = new StringBuilder(year).append(" 年合同应收与回款：")
                .append("\n合计：应收 ").append(totalReceivable.stripTrailingZeros().toPlainString())
                .append(" 元，已回款 ").append(totalReceived.stripTrailingZeros().toPlainString())
                .append(" 元，回款率 ").append(rate(totalReceived, totalReceivable)).append("%");
        for (Map.Entry<String, BigDecimal> e : receivableByLine.entrySet()) {
            BigDecimal received = receivedByLine.getOrDefault(e.getKey(), BigDecimal.ZERO);
            sb.append("\n- ").append(e.getKey()).append("：应收 ")
                    .append(e.getValue().stripTrailingZeros().toPlainString())
                    .append(" 元，回款率 ").append(rate(received, e.getValue())).append("%");
        }
        int top = Math.min(Math.max(intArg(args, "top", 5), 1), 10);
        List<RevenueContractEntry> unpaid = contracts.stream()
                .filter(c -> nvl(c.getReceivableAmount()).subtract(nvl(c.getReceivedAmount()))
                        .compareTo(BigDecimal.ZERO) > 0)
                .sorted((a, b) -> nvl(b.getReceivableAmount()).subtract(nvl(b.getReceivedAmount()))
                        .compareTo(nvl(a.getReceivableAmount()).subtract(nvl(a.getReceivedAmount()))))
                .limit(top)
                .toList();
        if (!unpaid.isEmpty()) {
            sb.append("\n\n未回款金额最大合同（Top ").append(unpaid.size()).append("）：");
            for (RevenueContractEntry c : unpaid) {
                BigDecimal gap = nvl(c.getReceivableAmount()).subtract(nvl(c.getReceivedAmount()));
                sb.append("\n- ").append(c.getContractName() == null ? c.getContractNo() : c.getContractName())
                        .append("：未回款 ").append(gap.stripTrailingZeros().toPlainString()).append(" 元");
            }
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    // ==================== 成本结构 ====================

    private AiAgentToolResult analyzeCostStructure(Long userId, JsonNode args) {
        if (!hasRevenuePermission(userId)) {
            return new AiAgentToolResult("成本分析需要「营收查看」权限，当前账号无权访问", true);
        }
        String month = normalizeReportMonth(textArg(args, "month"));
        List<RevenueCostEntry> entries = costMapper.selectList(
                new LambdaQueryWrapper<RevenueCostEntry>()
                        .eq(RevenueCostEntry::getYearMonth, month));
        if (entries.isEmpty()) {
            return new AiAgentToolResult(month + " 月暂无已同步的成本数据", false);
        }
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal direct = BigDecimal.ZERO;
        BigDecimal thirdParty = BigDecimal.ZERO;
        for (RevenueCostEntry c : entries) {
            labor = labor.add(nvl(c.getCostAmount()));
        }
        // 合同表的行级成本汇总（当月口径）
        List<RevenueContractEntry> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<RevenueContractEntry>()
                        .likeRight(RevenueContractEntry::getSaleMonth, month));
        for (RevenueContractEntry c : contracts) {
            direct = direct.add(nvl(c.getDirectCost()));
            thirdParty = thirdParty.add(nvl(c.getThirdPartyCost()));
        }
        BigDecimal total = labor.add(direct).add(thirdParty);
        StringBuilder sb = new StringBuilder(month).append(" 月成本结构（合计 ")
                .append(total.stripTrailingZeros().toPlainString()).append(" 元）：")
                .append("\n- 人力成本（工时折算）：").append(labor.stripTrailingZeros().toPlainString())
                .append(" 元（").append(rate(labor, total)).append("%）")
                .append("\n- 直接成本：").append(direct.stripTrailingZeros().toPlainString())
                .append(" 元（").append(rate(direct, total)).append("%）")
                .append("\n- 第三方成本：").append(thirdParty.stripTrailingZeros().toPlainString())
                .append(" 元（").append(rate(thirdParty, total)).append("%）");
        return new AiAgentToolResult(sb.toString(), false);
    }

    // ==================== KPI 周报摘要 ====================

    private AiAgentToolResult kpiSummary(Long userId, JsonNode args) {
        if (!hasRevenuePermission(userId)) {
            return new AiAgentToolResult("KPI 摘要需要「营收查看」权限，当前账号无权访问", true);
        }
        int year = intArg(args, "year", YearMonth.now().getYear());
        String groupFilter = textArg(args, "group");
        var vo = kpiReportService.buildReport(year);
        StringBuilder sb = new StringBuilder(year).append(" 年经营 KPI：");
        if (vo.getTotal() != null) {
            var t = vo.getTotal();
            sb.append("\n合计：营收 ").append(bd(t.getYtdRevenue())).append(" / 目标 ").append(bd(t.getRevenueTarget()))
                    .append(" 元（达成率 ").append(bd(t.getRevenueRate())).append("%），毛利 ")
                    .append(bd(t.getYtdProfit())).append(" / 目标 ").append(bd(t.getProfitTarget()))
                    .append(" 元（达成率 ").append(bd(t.getProfitRate())).append("%）");
        }
        for (var g : vo.getGroups()) {
            if (groupFilter != null && !groupFilter.isBlank()
                    && !groupFilter.equals(g.getReportGroup())) {
                continue;
            }
            var latest = g.getLatest();
            sb.append("\n- ").append(g.getReportGroup())
                    .append("：营收达成率 ").append(latest == null ? "—" : bd(latest.getRevenueRate())).append("%")
                    .append("，毛利达成率 ").append(latest == null ? "—" : bd(latest.getProfitRate())).append("%");
            if (latest != null && latest.getWeekDeltaProfit() != null
                    && latest.getWeekDeltaProfit().compareTo(BigDecimal.ZERO) != 0) {
                sb.append("，本周毛利增量 ").append(bd(latest.getWeekDeltaProfit())).append(" 元");
            }
            if (latest != null && latest.getNotes() != null && !latest.getNotes().isEmpty()) {
                var note = latest.getNotes().get(0);
                sb.append("，最新备注：").append(note.getMetric()).append(" ")
                        .append(note.getDeviationReason() == null ? "" : note.getDeviationReason());
            }
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    // ==================== 同步状态 ====================

    private AiAgentToolResult syncStatus(Long userId) {
        List<WorktimeSyncLog> logs = syncLogMapper.selectList(
                new LambdaQueryWrapper<WorktimeSyncLog>()
                        .orderByDesc(WorktimeSyncLog::getId)
                        .last("LIMIT 9"));
        if (logs.isEmpty()) {
            return new AiAgentToolResult("工时系统尚未执行过数据同步", false);
        }
        StringBuilder sb = new StringBuilder("工时系统最近同步：");
        for (WorktimeSyncLog log : logs) {
            sb.append("\n- ").append(log.getSyncType()).append("（").append(log.getScope() == null ? "全量" : log.getScope()).append("）：")
                    .append(log.getStatus())
                    .append("，落库 ").append(log.getUpsertCount()).append(" 行");
            if (log.getPendingCount() != null && log.getPendingCount() > 0) {
                sb.append("，待映射 ").append(log.getPendingCount()).append(" 行");
            }
            if (log.getStartedAt() != null) {
                sb.append("，").append(log.getStartedAt().toLocalDate()).append(" ").append(log.getStartedAt().toLocalTime());
            }
        }
        return new AiAgentToolResult(sb.toString(), false);
    }

    // ==================== 辅助 ====================

    private boolean hasRevenuePermission(Long userId) {
        List<String> permissions = sysRoleService.getPermissionCodesByUserId(userId);
        return permissions != null && permissions.contains("revenue:view");
    }

    private String normalizeReportMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now().minusMonths(1).toString();
        }
        try {
            return YearMonth.parse(month).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("月份格式无效，请使用 YYYY-MM");
        }
    }

    private BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal rate(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private String bd(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private String textArg(JsonNode args, String name) {
        JsonNode node = args.path(name);
        return node.isTextual() ? node.asText() : null;
    }

    private int intArg(JsonNode args, String name, int fallback) {
        JsonNode node = args.path(name);
        return node.canConvertToInt() ? node.asInt() : fallback;
    }

    private JsonNode objectSchema(Map<String, JsonNode> properties, List<String> required) {
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        properties.forEach(props::set);
        if (required != null && !required.isEmpty()) {
            var requiredNode = schema.putArray("required");
            required.forEach(requiredNode::add);
        }
        return schema;
    }

    private JsonNode stringProperty(String description) {
        var node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private JsonNode integerProperty(String description) {
        var node = objectMapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }
}
