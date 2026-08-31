package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueEstimateEntry;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueEstimateEntryMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.SalesOpportunityMapper;
import com.bu.management.vo.RevenueMatrixVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 营收矩阵汇总：完结月份取导入实际值（工时明细 + 成本明细），
 * 未完结月份取预估明细；行级单价 = 累计完结月成本 ÷ 累计完结人月。
 * 行键：p-<根项目ID> / lp-<业务线ID>（项目集）/ sp-<销售项目ID> / pool-<业务线ID> / other-<业务线ID>。
 */
@Service
@RequiredArgsConstructor
public class RevenueMatrixService {

    /** 营收口径项目合并：佳贝艾特/海普诺凯 为澳优的组成部分，矩阵统一并入澳优行 */
    private static final Map<String, String> PROJECT_ALIASES = Map.of(
            "佳贝艾特", "澳优",
            "海普诺凯", "澳优");

    private final RevenueWorklogEntryMapper worklogEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueEstimateEntryMapper estimateEntryMapper;
    private final RevenueSalesProjectMapper salesProjectMapper;
    private final RevenueMonthService monthService;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final SalesOpportunityMapper opportunityMapper;

    public RevenueMatrixVO getMatrix(int year) {
        List<String> monthKeys = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            monthKeys.add(String.format("%04d-%02d", year, m));
        }
        Set<String> closed = monthService.closedMonths();

        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1)
                .orderByAsc(BusinessLine::getId));
        Map<Long, String> lineModes = lines.stream()
                .collect(Collectors.toMap(BusinessLine::getId,
                        line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full",
                        (a, b) -> a));
        Map<Long, Project> projectsById = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));
        Map<Long, Long> aliasToRoot = aliasMap(projectsById);
        Map<Long, RevenueSalesProject> salesById = salesProjectMapper.selectList(null).stream()
                .collect(Collectors.toMap(RevenueSalesProject::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> opportunityNames = opportunityMapper.selectList(null).stream()
                .collect(Collectors.toMap(SalesOpportunity::getId, SalesOpportunity::getName, (a, b) -> a));

        List<RevenueWorklogEntry> worklogs = worklogEntryMapper.selectList(new LambdaQueryWrapper<RevenueWorklogEntry>()
                .eq(RevenueWorklogEntry::getPending, 0)
                .likeRight(RevenueWorklogEntry::getYearMonth, year + "-"));
        List<RevenueCostEntry> costs = costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getPending, 0)
                .likeRight(RevenueCostEntry::getYearMonth, year + "-"));
        List<RevenueEstimateEntry> estimates = estimateEntryMapper.selectList(new LambdaQueryWrapper<RevenueEstimateEntry>()
                .likeRight(RevenueEstimateEntry::getYearMonth, year + "-"));

        // 行注册：按业务线展示模式建行（full=项目+销售明细 / aggregate=两行聚合 / simple=单行）
        Map<Long, Map<String, RevenueMatrixVO.Row>> rowsByLine = new LinkedHashMap<>();
        for (BusinessLine line : lines) {
            Map<String, RevenueMatrixVO.Row> rows = new LinkedHashMap<>();
            String mode = lineModes.get(line.getId());
            if ("simple".equals(mode)) {
                rows.put("simple-" + line.getId(), newRow("simple-" + line.getId(), line.getName(), "simple", line.getId()));
            } else if ("aggregate".equals(mode)) {
                rows.put("agg-project-" + line.getId(), newRow("agg-project-" + line.getId(), "项目", "agg_project", line.getId()));
                rows.put("agg-sales-" + line.getId(), newRow("agg-sales-" + line.getId(), "销售", "agg_sales", line.getId()));
            } else {
                projectsById.values().stream()
                        .filter(p -> Objects.equals(p.getBusinessLineId(), line.getId()) && p.getParentId() == null)
                        .filter(p -> !aliasToRoot.containsKey(p.getId()))
                        .sorted(Comparator.comparing(Project::getId))
                        .forEach(p -> rows.put("p-" + p.getId(), newRow("p-" + p.getId(), p.getName(), "project", line.getId())));
                salesById.values().stream()
                        .filter(s -> Objects.equals(s.getBusinessLineId(), line.getId()))
                        .forEach(s -> {
                            RevenueMatrixVO.Row row = newRow("sp-" + s.getId(), s.getName(), "sales_specific", line.getId());
                            row.setSalesProjectId(s.getId());
                            row.setOpportunityId(s.getOpportunityId());
                            row.setOpportunityName(s.getOpportunityId() == null ? null : opportunityNames.get(s.getOpportunityId()));
                            rows.put(row.getRowKey(), row);
                        });
                rows.put("pool-" + line.getId(), newRow("pool-" + line.getId(), "商机集合", "pool", line.getId()));
                rows.put("other-" + line.getId(), newRow("other-" + line.getId(), "其他", "other", line.getId()));
            }
            rowsByLine.put(line.getId(), rows);
        }

        // 完结月实际值：工时明细出人月，成本明细出人月+成本
        Map<String, BigDecimal> worklogHours = new HashMap<>();
        for (RevenueWorklogEntry entry : worklogs) {
            if (!closed.contains(entry.getYearMonth()) || entry.getBusinessLineId() == null) {
                continue;
            }
            String key = cellKey(entry.getYearMonth(), rowKeyOf(entry.getBusinessLineId(), entry.getWorkType(),
                    entry.getSalesKind(), entry.getProjectId(), entry.getSalesProjectId(), projectsById, lineModes,
                    aliasToRoot));
            if (key != null) {
                worklogHours.merge(key, entry.getHours(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal[]> costValues = new HashMap<>();
        for (RevenueCostEntry entry : costs) {
            if (!closed.contains(entry.getYearMonth()) || entry.getBusinessLineId() == null) {
                continue;
            }
            String key = cellKey(entry.getYearMonth(), rowKeyOf(entry.getBusinessLineId(), entry.getWorkType(),
                    entry.getSalesKind(), entry.getProjectId(), entry.getSalesProjectId(), projectsById, lineModes,
                    aliasToRoot));
            if (key == null) {
                continue;
            }
            BigDecimal[] pair = costValues.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            pair[0] = pair[0].add(entry.getHours());
            pair[1] = pair[1].add(entry.getCostAmount());
        }

        // 未完结月预估
        Map<String, BigDecimal[]> estimateValues = new HashMap<>();
        Map<String, Integer> estimateCounts = new HashMap<>();
        for (RevenueEstimateEntry entry : estimates) {
            if (closed.contains(entry.getYearMonth())) {
                continue;
            }
            String rowKey = estimateRowKey(entry, projectsById, lineModes, aliasToRoot);
            String key = cellKey(entry.getYearMonth(), rowKey);
            BigDecimal[] pair = estimateValues.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            pair[0] = pair[0].add(entry.getPersonMonths());
            if (entry.getAmount() != null) {
                pair[1] = pair[1].add(entry.getAmount());
            }
            estimateCounts.merge(key, 1, Integer::sum);
        }

        // 行级累计完结单价
        Map<String, BigDecimal[]> closedTotals = new HashMap<>();
        costValues.forEach((key, pair) -> closedTotals.merge(rowPart(key), pair,
                (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])}));

        // 填格
        RevenueMatrixVO matrix = new RevenueMatrixVO();
        matrix.setYear(year);
        monthKeys.forEach(key -> {
            RevenueMatrixVO.MonthInfo info = new RevenueMatrixVO.MonthInfo();
            info.setYearMonth(key);
            info.setClosed(closed.contains(key));
            matrix.getMonths().add(info);
        });

        List<RevenueMatrixVO.Cell> grandMonths = emptyCells();
        BigDecimal[] grand = {BigDecimal.ZERO, BigDecimal.ZERO};
        BigDecimal[] overviewProject = {BigDecimal.ZERO};
        BigDecimal[] overviewSales = {BigDecimal.ZERO};

        for (BusinessLine line : lines) {
            Map<String, RevenueMatrixVO.Row> rows = rowsByLine.get(line.getId());
            List<RevenueMatrixVO.Cell> lineMonths = emptyCells();
            BigDecimal[] lineTotal = {BigDecimal.ZERO, BigDecimal.ZERO};

            for (RevenueMatrixVO.Row row : rows.values()) {
                row.setMonths(emptyCells());
                RevenueMatrixVO.Cell rowTotal = emptyCell();
                BigDecimal[] closedPair = closedTotals.get(row.getRowKey());
                if (closedPair != null && closedPair[0].compareTo(BigDecimal.ZERO) > 0) {
                    row.setUnitPrice(closedPair[1].divide(closedPair[0], 2, RoundingMode.HALF_UP));
                }
                for (int i = 0; i < 12; i++) {
                    String monthKey = monthKeys.get(i);
                    String key = cellKey(monthKey, row.getRowKey());
                    RevenueMatrixVO.Cell cell = row.getMonths().get(i);
                    if (closed.contains(monthKey)) {
                        BigDecimal[] cost = costValues.get(key);
                        BigDecimal hours = worklogHours.getOrDefault(key, cost == null ? null : cost[0]);
                        if (hours != null || cost != null) {
                            cell.setSource("actual");
                            cell.setHours(hours != null ? hours : BigDecimal.ZERO);
                            cell.setCost(cost == null ? BigDecimal.ZERO : cost[1]);
                        }
                    } else {
                        BigDecimal[] estimate = estimateValues.get(key);
                        if (estimate != null) {
                            cell.setSource("estimate");
                            cell.setHours(estimate[0]);
                            cell.setCost(estimate[1]);
                            cell.setEstimateCount(estimateCounts.get(key));
                        }
                    }
                    addCell(rowTotal, cell);
                    addCell(lineMonths.get(i), cell);
                    addCell(grandMonths.get(i), cell);
                }
                row.setTotals(rowTotal);
                lineTotal[0] = lineTotal[0].add(rowTotal.getHours());
                lineTotal[1] = lineTotal[1].add(rowTotal.getCost());
                if (isProjectKind(row.getKind())) {
                    overviewProject[0] = overviewProject[0].add(rowTotal.getHours());
                } else if (!"simple".equals(row.getKind())) {
                    overviewSales[0] = overviewSales[0].add(rowTotal.getHours());
                } else {
                    overviewProject[0] = overviewProject[0].add(rowTotal.getHours());
                }
            }

            RevenueMatrixVO.LineBlock block = new RevenueMatrixVO.LineBlock();
            block.setBusinessLineId(line.getId());
            block.setBusinessLineName(line.getName());
            block.setMonthTotals(lineMonths);
            block.setTotals(cellOf(lineTotal[0], lineTotal[1], null));
            RevenueMatrixVO.Section projectSection = new RevenueMatrixVO.Section();
            projectSection.setType("project");
            RevenueMatrixVO.Section salesSection = new RevenueMatrixVO.Section();
            salesSection.setType("sales");
            rows.values().forEach(row -> {
                if (isProjectKind(row.getKind()) || "simple".equals(row.getKind())) {
                    projectSection.getRows().add(row);
                } else {
                    salesSection.getRows().add(row);
                }
            });
            block.setSections(List.of(projectSection, salesSection));
            block.setMode(lineModes.get(line.getId()));
            matrix.getLines().add(block);
            grand[0] = grand[0].add(lineTotal[0]);
            grand[1] = grand[1].add(lineTotal[1]);
        }

        matrix.setMonthTotals(grandMonths);
        matrix.setGrandTotal(cellOf(grand[0], grand[1], null));
        RevenueMatrixVO.Overview overview = new RevenueMatrixVO.Overview();
        overview.setTotalHours(grand[0]);
        overview.setProjectHours(overviewProject[0]);
        overview.setSalesHours(overviewSales[0]);
        overview.setTotalCost(grand[1]);
        overview.setAvgUnitPrice(grand[0].compareTo(BigDecimal.ZERO) > 0
                ? grand[1].divide(grand[0], 2, RoundingMode.HALF_UP) : null);
        overview.setClosedMonthCount((int) monthKeys.stream().filter(closed::contains).count());
        matrix.setOverview(overview);
        return matrix;
    }

    /** 单元格下钻：完结月返回工时/成本明细，未完结月返回预估明细 */
    public Map<String, Object> cellDetail(String yearMonth, Long businessLineId, String rowKey) {
        Map<String, Object> result = new HashMap<>();
        boolean closed = monthService.isClosed(yearMonth);
        result.put("closed", closed);
        if (closed) {
            List<RevenueWorklogEntry> worklogEntries = worklogEntryMapper.selectList(
                    new LambdaQueryWrapper<RevenueWorklogEntry>()
                            .eq(RevenueWorklogEntry::getYearMonth, yearMonth)
                            .eq(RevenueWorklogEntry::getBusinessLineId, businessLineId)
                            .eq(RevenueWorklogEntry::getPending, 0))
                    .stream()
                    .filter(entry -> rowKeyMatches(entry.getWorkType(), entry.getSalesKind(),
                            entry.getProjectId(), entry.getSalesProjectId(), businessLineId, rowKey))
                    .toList();
            result.put("worklogEntries", worklogEntries);
            result.put("costEntries", costEntryMapper.selectList(
                    new LambdaQueryWrapper<RevenueCostEntry>()
                            .eq(RevenueCostEntry::getYearMonth, yearMonth)
                            .eq(RevenueCostEntry::getBusinessLineId, businessLineId)
                            .eq(RevenueCostEntry::getPending, 0))
                    .stream()
                    .filter(entry -> rowKeyMatches(entry.getWorkType(), entry.getSalesKind(),
                            entry.getProjectId(), entry.getSalesProjectId(), businessLineId, rowKey))
                    .toList());
        } else {
            result.put("estimates", estimateEntryMapper.selectList(
                    new LambdaQueryWrapper<RevenueEstimateEntry>()
                            .eq(RevenueEstimateEntry::getYearMonth, yearMonth)
                            .eq(RevenueEstimateEntry::getBusinessLineId, businessLineId))
                    .stream()
                    .filter(entry -> estimateRowKey(entry, rootProjectMap(), lineModes()).equals(rowKey))
                    .toList());
        }
        return result;
    }

    private Map<Long, Project> rootProjectMap() {
        return projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));
    }

    private boolean rowKeyMatches(String workType, String salesKind, Long projectId, Long salesProjectId,
                                  Long businessLineId, String rowKey) {
        Map<Long, Project> projectsById = rootProjectMap();
        String key = rowKeyOf(businessLineId, workType, salesKind, projectId, salesProjectId,
                projectsById, lineModes(), aliasMap(projectsById));
        return Objects.equals(key, rowKey);
    }

    /** 别名归并：源项目 ID → 目标根项目 ID（同业务线内按名称解析；目标缺失则不合并） */
    private Map<Long, Long> aliasMap(Map<Long, Project> projectsById) {
        Map<Long, Long> aliasToRoot = new HashMap<>();
        PROJECT_ALIASES.forEach((sourceName, targetName) -> {
            List<Project> sources = projectsById.values().stream()
                    .filter(p -> sourceName.equals(p.getName())).toList();
            for (Project source : sources) {
                projectsById.values().stream()
                        .filter(p -> targetName.equals(p.getName())
                                && Objects.equals(p.getBusinessLineId(), source.getBusinessLineId()))
                        .findFirst()
                        .ifPresent(target -> aliasToRoot.put(source.getId(), target.getId()));
            }
        });
        return aliasToRoot;
    }

    private Map<Long, String> lineModes() {
        return businessLineMapper.selectList(null).stream()
                .collect(Collectors.toMap(BusinessLine::getId,
                        line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full",
                        (a, b) -> a));
    }

    private boolean isProjectKind(String kind) {
        return "project".equals(kind) || "agg_project".equals(kind) || "line_pool".equals(kind);
    }

    private String rowKeyOf(Long businessLineId, String workType, String salesKind, Long projectId,
                            Long salesProjectId, Map<Long, Project> projectsById, Map<Long, String> lineModes) {
        return rowKeyOf(businessLineId, workType, salesKind, projectId, salesProjectId, projectsById, lineModes,
                Map.of());
    }

    private String rowKeyOf(Long businessLineId, String workType, String salesKind, Long projectId,
                            Long salesProjectId, Map<Long, Project> projectsById, Map<Long, String> lineModes,
                            Map<Long, Long> aliasToRoot) {
        String mode = lineModes.getOrDefault(businessLineId, "full");
        if ("simple".equals(mode)) {
            return "simple-" + businessLineId;
        }
        if ("aggregate".equals(mode)) {
            return "project".equals(workType) ? "agg-project-" + businessLineId : "agg-sales-" + businessLineId;
        }
        if ("sales".equals(workType)) {
            if ("specific".equals(salesKind) && salesProjectId != null) {
                return "sp-" + salesProjectId;
            }
            if ("pool".equals(salesKind)) {
                return "pool-" + businessLineId;
            }
            return "other-" + businessLineId;
        }
        // full 模式无项目集行：业务线级【项目】并入「其他」
        if (projectId == null) {
            return "other-" + businessLineId;
        }
        Project project = projectsById.get(projectId);
        while (project != null && project.getParentId() != null) {
            project = projectsById.get(project.getParentId());
        }
        if (project == null) {
            return null;
        }
        Long rootId = aliasToRoot.getOrDefault(project.getId(), project.getId());
        return "p-" + rootId;
    }

    private String estimateRowKey(RevenueEstimateEntry entry, Map<Long, Project> projectsById,
                                  Map<Long, String> lineModes) {
        return estimateRowKey(entry, projectsById, lineModes, Map.of());
    }

    private String estimateRowKey(RevenueEstimateEntry entry, Map<Long, Project> projectsById,
                                  Map<Long, String> lineModes, Map<Long, Long> aliasToRoot) {
        return rowKeyOf(entry.getBusinessLineId(), entry.getWorkType(), entry.getSalesKind(),
                entry.getProjectId(), entry.getSalesProjectId(), projectsById, lineModes, aliasToRoot);
    }

    private RevenueMatrixVO.Row newRow(String rowKey, String name, String kind, Long businessLineId) {
        RevenueMatrixVO.Row row = new RevenueMatrixVO.Row();
        row.setRowKey(rowKey);
        row.setName(name);
        row.setKind(kind);
        if (rowKey.startsWith("p-")) {
            row.setProjectId(Long.valueOf(rowKey.substring(2)));
        }
        return row;
    }

    private String cellKey(String yearMonth, String rowKey) {
        return yearMonth + "|" + rowKey;
    }

    private String rowPart(String cellKey) {
        return cellKey.substring(cellKey.indexOf('|') + 1);
    }

    private RevenueMatrixVO.Cell emptyCell() {
        return cellOf(BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    private List<RevenueMatrixVO.Cell> emptyCells() {
        List<RevenueMatrixVO.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            cells.add(emptyCell());
        }
        return cells;
    }

    private RevenueMatrixVO.Cell cellOf(BigDecimal hours, BigDecimal cost, String source) {
        RevenueMatrixVO.Cell cell = new RevenueMatrixVO.Cell();
        cell.setHours(hours);
        cell.setCost(cost);
        cell.setSource(source);
        return cell;
    }

    private void addCell(RevenueMatrixVO.Cell target, RevenueMatrixVO.Cell source) {
        if (source.getSource() == null) {
            return;
        }
        target.setHours(target.getHours().add(source.getHours()));
        target.setCost(target.getCost().add(source.getCost()));
        if (target.getSource() == null) {
            target.setSource(source.getSource());
        } else if (!target.getSource().equals(source.getSource())) {
            target.setSource("mixed");
        }
    }
}
