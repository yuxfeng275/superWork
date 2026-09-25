package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.ProjectProfitAllocationBatchRequest;
import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectProfitAllocation;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueMonthClose;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.mapper.BizLineProfitReportMapper;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectProfitAllocationMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import com.bu.management.vo.ProjectProfitMonthSyncVO;
import com.bu.management.vo.ProjectProfitReportVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目利润表（月份 × 业务线 × 分类 × 项目），表头固定 15 列。
 * <p>口径：</p>
 * <ul>
 *   <li>报表月份轴只由工时系统业务线利润镜像（biz_line_profit_report）已同步月份驱动；
 *       合计行整行取镜像值（未税），与《业务线营收利润月度汇总表》天然对齐。</li>
 *   <li>full 线（云鹿Saas/定制）项目行营收 = OA 合同已交付金额（含税，receivable_amount 按 delivery_date 落月、
 *       交付日期不超过今天，与「交付与利润」同口径）÷(1+业务线 taxRate/100) 换算为未税，
 *       与镜像合计（财报未税）同口径可比；6 个成本列支持按 月×项目 手动分配（project_profit_allocation，财报口径未税）。</li>
 *   <li>aggregate 线（会员通）「项目集」行、simple 线（精准）单行：营收与 6 成本列直接取镜像线级值（该行即整线）。</li>
 *   <li>销售行：「销售」= 该线全部 work_type=sales 工时成本（营收为 0）；「业务线」= work_type=project 且
 *       project_id 为空的工时成本（投入到其他事项）；aggregate 线无「业务线」行（并入项目集），simple 线无销售行。</li>
 *   <li>差额行 = 合计 − Σ(当前返回的项目行+销售行)，随 projectIds/categories 过滤按显示行重算。</li>
 *   <li>考核毛利 = 营业收入 − 短信成本 − 直接成本 − 平台佣金 − 赔付 − 协力外包 − 软件赠送 − 成本(人工)，
 *       每行按公式重算；率 = 毛利/营收×100（HALF_UP 2 位，营收为 0/null 时率为 null）。</li>
 * </ul>
 * <p>项目别名归并（佳贝艾特/海普诺凯 → 澳优）与 rootIdOf 归并逻辑复制自
 * {@link RevenueDeliverySummaryService}（仅复制小工具，不改原类）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectProfitService {

    /** 可手动分配的 6 个成本列 */
    public static final Set<String> COST_TYPES = Set.of(
            "sms", "direct", "platform_fee", "compensation", "outsourcing", "software_gift");

    /** 复制自 RevenueDeliverySummaryService.PROJECT_ALIASES：别名项目（源）并入同业务线目标主项目行 */
    private static final Map<String, String> PROJECT_ALIASES = Map.of(
            "佳贝艾特", "澳优",
            "海普诺凯", "澳优");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Pattern YM_PATTERN = Pattern.compile("\\d{4}-\\d{2}");
    private static final String MODE_FULL = "full";
    private static final String MODE_AGGREGATE = "aggregate";
    private static final String MODE_SIMPLE = "simple";
    private static final String ROW_PROJECT = "PROJECT";
    private static final String ROW_SALES = "SALES";
    private static final String ROW_LINE_OTHER = "LINE_OTHER";
    private static final String ROW_TOTAL = "TOTAL";
    private static final String ROW_RESIDUAL = "RESIDUAL";
    private static final List<String> PERIOD_ORDER = List.of("H1", "H2", "YEAR");

    private final BizLineProfitReportMapper reportMapper;
    private final BusinessLineMapper businessLineMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final ProjectProfitAllocationMapper allocationMapper;
    private final ProjectMapper projectMapper;
    private final WorktimeMonthlySyncService worktimeMonthlySyncService;
    private final BusinessLineProfitService businessLineProfitService;
    private final RevenueMonthCloseMapper monthCloseMapper;

    // ==================== 报表查询 ====================

    public ProjectProfitReportVO query(int year, List<Integer> months, List<String> periods,
                                       List<Long> businessLineIds, List<String> categories, List<Long> projectIds) {
        Dataset ds = loadDataset(year);
        Filters filters = new Filters(
                businessLineIds == null ? Set.of() : Set.copyOf(businessLineIds),
                categories == null ? Set.of() : Set.copyOf(categories),
                projectIds == null ? Set.of() : Set.copyOf(projectIds));

        ProjectProfitReportVO vo = new ProjectProfitReportVO();
        vo.setYear(year);
        vo.setAvailableMonths(ds.availableMonths);
        vo.setLastSyncedAt(ds.lastSyncedAt);
        vo.setLineOptions(buildLineOptions(ds));

        List<ProjectProfitReportVO.Block> blocks = new ArrayList<>();
        List<String> requestedPeriods = normalizePeriods(periods);
        if (!requestedPeriods.isEmpty()) {
            for (String period : requestedPeriods) {
                List<String> periodMonths = periodMonths(year, period).stream()
                        .filter(ds.availableMonths::contains)
                        .toList();
                if (periodMonths.isEmpty()) {
                    continue;
                }
                blocks.add(buildPeriodBlock(period, periodMonths, ds, filters));
            }
        } else if (months != null && !months.isEmpty()) {
            for (Integer month : months.stream().filter(Objects::nonNull).distinct().sorted().toList()) {
                String yearMonth = String.format("%d-%02d", year, month);
                if (ds.availableMonths.contains(yearMonth)) {
                    blocks.add(buildMonthBlock(yearMonth, ds, filters));
                }
            }
        } else {
            for (String yearMonth : ds.availableMonths) {
                blocks.add(buildMonthBlock(yearMonth, ds, filters));
            }
        }
        vo.setBlocks(blocks);
        return vo;
    }

    /**
     * 单月块构建（query 路径与 syncMonth 对齐计算共用同一份实现，禁止复制第二份）。
     * 仅纳入当月存在镜像行的业务线；镜像缺失的月份返回空 lines。
     */
    private ProjectProfitReportVO.Block buildMonthBlock(String yearMonth, Dataset ds, Filters filters) {
        ProjectProfitReportVO.Block block = new ProjectProfitReportVO.Block();
        block.setKey(yearMonth);
        block.setLabel(Integer.parseInt(yearMonth.substring(5)) + "月");
        List<ProjectProfitReportVO.Line> lines = new ArrayList<>();
        for (BusinessLine line : ds.managedLines) {
            if (!filters.businessLineIds.isEmpty() && !filters.businessLineIds.contains(line.getId())) {
                continue;
            }
            MirrorAcc mirror = ds.mirror(yearMonth, line.getId());
            if (mirror == null) {
                continue;
            }
            List<ProjectProfitReportVO.Row> rows = buildMonthRows(line, yearMonth, ds, mirror);
            lines.add(assembleLine(line, rows, mirrorTotal(mirror), filters));
        }
        block.setLines(lines);
        return block;
    }

    /** 期间聚合块（H1/H2/全年）：行按 (rowType, projectId) 跨月求和后重算毛利/率；合计 = 镜像跨月求和 */
    private ProjectProfitReportVO.Block buildPeriodBlock(String period, List<String> periodMonths,
                                                         Dataset ds, Filters filters) {
        ProjectProfitReportVO.Block block = new ProjectProfitReportVO.Block();
        block.setKey(period);
        block.setLabel("YEAR".equals(period) ? "全年" : period);
        List<ProjectProfitReportVO.Line> lines = new ArrayList<>();
        for (BusinessLine line : ds.managedLines) {
            if (!filters.businessLineIds.isEmpty() && !filters.businessLineIds.contains(line.getId())) {
                continue;
            }
            MirrorAcc mirrorSum = null;
            Map<String, ProjectProfitReportVO.Row> merged = new LinkedHashMap<>();
            for (String yearMonth : periodMonths) {
                MirrorAcc mirror = ds.mirror(yearMonth, line.getId());
                if (mirror == null) {
                    continue;
                }
                if (mirrorSum == null) {
                    mirrorSum = new MirrorAcc();
                }
                mirrorSum.add(mirror);
                for (ProjectProfitReportVO.Row row : buildMonthRows(line, yearMonth, ds, mirror)) {
                    mergeRow(merged, row);
                }
            }
            if (mirrorSum == null) {
                continue;
            }
            List<ProjectProfitReportVO.Row> rows = new ArrayList<>(merged.values());
            rows.forEach(this::finalizeRow);
            lines.add(assembleLine(line, rows, mirrorTotal(mirrorSum), filters));
        }
        block.setLines(lines);
        return block;
    }

    /** 单线单月明细行（项目行… → 销售行 → 业务线行），未过滤、未算差额 */
    private List<ProjectProfitReportVO.Row> buildMonthRows(BusinessLine line, String yearMonth,
                                                           Dataset ds, MirrorAcc mirror) {
        String mode = modeOf(line);
        List<RevenueCostEntry> costs = ds.costs(yearMonth, line.getId());
        List<ProjectProfitReportVO.Row> rows = new ArrayList<>();
        if (MODE_SIMPLE.equals(mode)) {
            // 精准：单行合并 project+sales 工时成本；营收与 6 成本列取镜像线级值
            ProjectProfitReportVO.Row row = newRow(ROW_PROJECT, "项目", null, line.getName(), false);
            for (RevenueCostEntry entry : costs) {
                addLabor(row, entry);
            }
            applyMirror(row, mirror);
            finalizeRow(row);
            rows.add(row);
            return rows;
        }
        if (MODE_AGGREGATE.equals(mode)) {
            // 会员通：「项目集」行即整线（含 project_id 为空的 project 行）；营收与 6 成本列取镜像
            ProjectProfitReportVO.Row agg = newRow(ROW_PROJECT, "项目", null, "项目集", false);
            BigDecimal salesHours = BigDecimal.ZERO;
            BigDecimal salesCost = BigDecimal.ZERO;
            for (RevenueCostEntry entry : costs) {
                if (isSales(entry.getWorkType())) {
                    salesHours = salesHours.add(nz(entry.getHours()));
                    salesCost = salesCost.add(nz(entry.getCostAmount()));
                } else {
                    addLabor(agg, entry);
                }
            }
            applyMirror(agg, mirror);
            finalizeRow(agg);
            rows.add(agg);
            rows.add(salesRow(salesHours, salesCost));
            return rows;
        }
        // full：每个根项目一行（别名归并），工时/成本取 revenue_cost_entry，营收取 OA 合同（含税→未税），6 成本列取手动分配
        Map<Long, ProjectProfitReportVO.Row> projectRows = new LinkedHashMap<>();
        for (Project root : ds.rootProjects(line.getId())) {
            ProjectProfitReportVO.Row row = newRow(ROW_PROJECT, "项目", root.getId(), root.getName(), true);
            row.setRevenue(exTax(ds.contractInclTax(yearMonth, root.getId()), ds.taxDivisor(line.getId())));
            applyAllocations(row, ds.allocations(yearMonth, root.getId()));
            projectRows.put(root.getId(), row);
        }
        ProjectProfitReportVO.Row sales = salesRow(BigDecimal.ZERO, BigDecimal.ZERO);
        ProjectProfitReportVO.Row lineOther = newRow(ROW_LINE_OTHER, "销售", null, "业务线", false);
        for (RevenueCostEntry entry : costs) {
            if (isSales(entry.getWorkType())) {
                addLabor(sales, entry);
                continue;
            }
            if (entry.getProjectId() == null) {
                // 业务线级项目工时（投入到其他事项）→「销售-业务线」行
                addLabor(lineOther, entry);
                continue;
            }
            Long root = ds.rootOf(entry.getProjectId());
            ProjectProfitReportVO.Row row = root == null ? null : projectRows.get(root);
            if (row != null) {
                addLabor(row, entry);
            }
            // 项目缺失/跨线等无法归桶的行落入差额行（合计 − Σ明细）暴露
        }
        projectRows.values().forEach(this::finalizeRow);
        finalizeRow(sales);
        finalizeRow(lineOther);
        rows.addAll(projectRows.values());
        rows.add(sales);
        rows.add(lineOther);
        return rows;
    }

    /** 组装 Line：应用行过滤后重算差额行（= 合计 − Σ显示明细行） */
    private ProjectProfitReportVO.Line assembleLine(BusinessLine line, List<ProjectProfitReportVO.Row> rows,
                                                    ProjectProfitReportVO.Row total, Filters filters) {
        List<ProjectProfitReportVO.Row> displayed = rows.stream()
                .filter(row -> keepRow(row, filters))
                .toList();
        ProjectProfitReportVO.Line out = new ProjectProfitReportVO.Line();
        out.setBusinessLineId(line.getId());
        out.setBusinessLineName(line.getName());
        out.setRevenueMode(modeOf(line));
        out.setRows(displayed);
        out.setTotal(total);
        ProjectProfitReportVO.Row residual = newRow(ROW_RESIDUAL, "差额", null, "未分配", false);
        addInto(residual, total);
        for (ProjectProfitReportVO.Row row : displayed) {
            subtract(residual, row);
        }
        finalizeRow(residual);
        out.setResidual(residual);
        return out;
    }

    private boolean keepRow(ProjectProfitReportVO.Row row, Filters filters) {
        if (!filters.categories.isEmpty()) {
            if (ROW_PROJECT.equals(row.getRowType()) && !filters.categories.contains("项目")) {
                return false;
            }
            if ((ROW_SALES.equals(row.getRowType()) || ROW_LINE_OTHER.equals(row.getRowType()))
                    && !filters.categories.contains("销售")) {
                return false;
            }
        }
        // projectIds 仅过滤 PROJECT 行（销售/合计/差额保留）
        return filters.projectIds.isEmpty() || !ROW_PROJECT.equals(row.getRowType())
                || (row.getProjectId() != null && filters.projectIds.contains(row.getProjectId()));
    }

    // ==================== 月度一键同步（决策4） ====================

    /**
     * 月度一键同步：工时明细+成本分析重拉（同一管道，含销售工时）→ 业务线利润镜像刷新 → 当月逐业务线对齐。
     * 任一步失败仅体现在对应 log.status='failed'，方法本身不抛业务异常（仅参数校验抛 IllegalArgumentException）。
     */
    public ProjectProfitMonthSyncVO syncMonth(String yearMonth) {
        if (!StringUtils.hasText(yearMonth) || !YM_PATTERN.matcher(yearMonth.trim()).matches()) {
            throw new IllegalArgumentException("月份格式必须是 YYYY-MM");
        }
        final YearMonth target;
        try {
            target = YearMonth.parse(yearMonth.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("月份无效: " + yearMonth);
        }
        if (target.isAfter(YearMonth.now())) {
            throw new IllegalArgumentException("不能同步未来月份: " + yearMonth);
        }
        String normalized = target.format(MONTH_FMT);

        boolean monthClosed = monthCloseMapper.selectCount(new LambdaQueryWrapper<RevenueMonthClose>()
                .eq(RevenueMonthClose::getYearMonth, normalized)
                .isNotNull(RevenueMonthClose::getClosedAt)) > 0;

        // 1) 工时明细 + 成本分析（含销售工时）整月重拉；已完结月在内部被跳过（返回空 logs）。
        //    syncConfirmedMonths 入口的 available-months 拉取未捕获异常（如工时系统不可达），
        //    此处兜底为 failed 日志，保证编排继续、方法不抛业务异常。
        List<WorktimeSyncLog> logs = new ArrayList<>();
        try {
            logs.addAll(worktimeMonthlySyncService.syncConfirmedMonths(normalized, "manual"));
        } catch (RuntimeException ex) {
            log.error("月度一键同步：工时/成本重拉失败 {}", normalized, ex);
            WorktimeSyncLog failed = new WorktimeSyncLog();
            failed.setSyncType("worklog+cost");
            failed.setScope(normalized);
            failed.setStatus("failed");
            failed.setMessage(ex.getMessage() == null ? "工时系统同步失败" : ex.getMessage());
            failed.setTriggeredBy("manual");
            logs.add(failed);
        }
        // 2) 业务线利润镜像刷新（合计行权威来源），必须在第 1 步之后：镜像与明细同源同月刷新，差额才是当期真实对齐状态
        logs.add(businessLineProfitService.syncMonth(normalized, "manual"));

        // 3) 对齐计算：复用单月块构建；镜像无该月数据时 lines 为空列表，不报错
        Dataset ds = loadDataset(target.getYear());
        ProjectProfitReportVO.Block block = buildMonthBlock(normalized, ds, Filters.NONE);
        List<ProjectProfitMonthSyncVO.LineAlignment> lines = new ArrayList<>();
        for (ProjectProfitReportVO.Line line : block.getLines()) {
            ProjectProfitReportVO.Row residual = line.getResidual();
            ProjectProfitMonthSyncVO.LineAlignment alignment = new ProjectProfitMonthSyncVO.LineAlignment();
            alignment.setBusinessLineId(line.getBusinessLineId());
            alignment.setBusinessLineName(line.getBusinessLineName());
            BigDecimal revenueResidual = nz(residual.getRevenue());
            BigDecimal costResidual = nz(residual.getCost());
            BigDecimal hoursResidual = nz(residual.getHours());
            alignment.setRevenueResidual(revenueResidual);
            alignment.setCostResidual(costResidual);
            alignment.setHoursResidual(hoursResidual);
            alignment.setAligned(revenueResidual.abs().compareTo(new BigDecimal("0.01")) < 0
                    && costResidual.abs().compareTo(new BigDecimal("0.01")) < 0
                    && hoursResidual.abs().compareTo(new BigDecimal("0.0001")) < 0);
            lines.add(alignment);
        }

        ProjectProfitMonthSyncVO vo = new ProjectProfitMonthSyncVO();
        vo.setYearMonth(normalized);
        vo.setMonthClosed(monthClosed);
        vo.setLogs(logs);
        vo.setLines(lines);
        return vo;
    }

    // ==================== 成本手动分配 ====================

    public List<ProjectProfitAllocation> listAllocations(String yearMonth, Long businessLineId) {
        return allocationMapper.selectList(new LambdaQueryWrapper<ProjectProfitAllocation>()
                .eq(StringUtils.hasText(yearMonth), ProjectProfitAllocation::getYearMonth, yearMonth)
                .eq(businessLineId != null, ProjectProfitAllocation::getBusinessLineId, businessLineId)
                .orderByAsc(ProjectProfitAllocation::getProjectId)
                .orderByAsc(ProjectProfitAllocation::getCostType));
    }

    /** 按 (year_month, project_id, cost_type) 唯一键 upsert：存在则 update amount/note，不存在 insert */
    public List<ProjectProfitAllocation> saveAllocations(ProjectProfitAllocationBatchRequest request) {
        if (request == null || !StringUtils.hasText(request.getYearMonth())
                || !YM_PATTERN.matcher(request.getYearMonth().trim()).matches()) {
            throw new IllegalArgumentException("月份格式必须是 YYYY-MM");
        }
        if (request.getBusinessLineId() == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        BusinessLine line = businessLineMapper.selectById(request.getBusinessLineId());
        if (line == null) {
            throw new IllegalArgumentException("业务线不存在");
        }
        if (!MODE_FULL.equals(modeOf(line))) {
            throw new IllegalArgumentException("仅 full 模式业务线（云鹿Saas/定制）支持项目成本分配");
        }
        if (request.getProjectId() == null) {
            throw new IllegalArgumentException("项目不能为空");
        }
        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("项目不存在");
        }
        if (!request.getBusinessLineId().equals(project.getBusinessLineId())) {
            throw new IllegalArgumentException("项目不属于该业务线");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("分配明细不能为空");
        }
        String yearMonth = request.getYearMonth().trim();
        List<ProjectProfitAllocation> saved = new ArrayList<>();
        for (ProjectProfitAllocationBatchRequest.Item item : request.getItems()) {
            if (item == null || !COST_TYPES.contains(item.getCostType())) {
                throw new IllegalArgumentException("非法成本类型: " + (item == null ? null : item.getCostType()));
            }
            if (item.getAmount() == null) {
                throw new IllegalArgumentException("分配金额不能为空");
            }
            ProjectProfitAllocation existing = allocationMapper.selectOne(
                    new LambdaQueryWrapper<ProjectProfitAllocation>()
                            .eq(ProjectProfitAllocation::getYearMonth, yearMonth)
                            .eq(ProjectProfitAllocation::getProjectId, request.getProjectId())
                            .eq(ProjectProfitAllocation::getCostType, item.getCostType())
                            .last("LIMIT 1"));
            if (existing != null) {
                existing.setAmount(item.getAmount());
                existing.setNote(item.getNote());
                allocationMapper.updateById(existing);
                saved.add(existing);
            } else {
                ProjectProfitAllocation created = new ProjectProfitAllocation();
                created.setYearMonth(yearMonth);
                created.setBusinessLineId(request.getBusinessLineId());
                created.setProjectId(request.getProjectId());
                created.setCostType(item.getCostType());
                created.setAmount(item.getAmount());
                created.setNote(item.getNote());
                allocationMapper.insert(created);
                saved.add(created);
            }
        }
        return saved;
    }

    public void deleteAllocation(Long id) {
        allocationMapper.deleteById(id);
    }

    // ==================== 数据加载 ====================

    private Dataset loadDataset(int year) {
        Dataset ds = new Dataset();
        // 与 BusinessLineProfitService.queryYear 同一过滤：status=1 且配置 KPI 报表分组，保证两页业务线集合一致
        ds.managedLines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1)
                .isNotNull(BusinessLine::getKpiReportGroup)
                .orderByAsc(BusinessLine::getId));
        if (ds.managedLines.isEmpty()) {
            return ds;
        }
        Set<Long> managedIds = ds.managedLines.stream().map(BusinessLine::getId).collect(Collectors.toSet());

        // 镜像行：可能多条工时系统业务线映射到同一本系统业务线，按 (月, 业务线) 求和
        List<BizLineProfitReport> mirrorRows = reportMapper.selectList(new LambdaQueryWrapper<BizLineProfitReport>()
                .likeRight(BizLineProfitReport::getYearMonth, year + "-")
                .in(BizLineProfitReport::getBusinessLineId, managedIds));
        for (BizLineProfitReport row : mirrorRows) {
            ds.mirror.computeIfAbsent(row.getYearMonth(), k -> new HashMap<>())
                    .computeIfAbsent(row.getBusinessLineId(), k -> new MirrorAcc())
                    .add(row);
            if (row.getSyncedAt() != null && (ds.lastSyncedAt == null || row.getSyncedAt().isAfter(ds.lastSyncedAt))) {
                ds.lastSyncedAt = row.getSyncedAt();
            }
        }
        ds.availableMonths = new ArrayList<>(new TreeSet<>(ds.mirror.keySet()));

        if (!ds.availableMonths.isEmpty()) {
            for (RevenueCostEntry entry : costEntryMapper.selectList(new LambdaQueryWrapper<RevenueCostEntry>()
                    .in(RevenueCostEntry::getYearMonth, ds.availableMonths)
                    .in(RevenueCostEntry::getBusinessLineId, managedIds)
                    .eq(RevenueCostEntry::getPending, 0))) {
                ds.costs.computeIfAbsent(entry.getYearMonth(), k -> new HashMap<>())
                        .computeIfAbsent(entry.getBusinessLineId(), k -> new ArrayList<>())
                        .add(entry);
            }
            for (ProjectProfitAllocation allocation : allocationMapper.selectList(
                    new LambdaQueryWrapper<ProjectProfitAllocation>()
                            .in(ProjectProfitAllocation::getYearMonth, ds.availableMonths))) {
                ds.allocations.computeIfAbsent(allocation.getYearMonth(), k -> new HashMap<>())
                        .computeIfAbsent(allocation.getProjectId(), k -> new HashMap<>())
                        .merge(allocation.getCostType(), nz(allocation.getAmount()), BigDecimal::add);
            }
        }

        // 项目与别名归并（复制自 RevenueDeliverySummaryService：rootIdOf / aliasMap）
        ds.projectsById = projectMapper.selectList(null).stream()
                .filter(p -> managedIds.contains(p.getBusinessLineId()))
                .collect(Collectors.toMap(Project::getId, p -> p, (a, b) -> a));
        ds.aliasToRoot = aliasMap(ds.projectsById);
        for (BusinessLine line : ds.managedLines) {
            List<Project> roots = ds.projectsById.values().stream()
                    .filter(p -> Objects.equals(p.getBusinessLineId(), line.getId()) && p.getParentId() == null)
                    .filter(p -> !ds.aliasToRoot.containsKey(p.getId()))
                    .sorted(Comparator.comparing(Project::getId))
                    .toList();
            ds.rootProjects.put(line.getId(), roots);
            BigDecimal rate = line.getTaxRate() == null ? BigDecimal.ZERO : line.getTaxRate();
            ds.taxDivisor.put(line.getId(),
                    BigDecimal.ONE.add(rate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
        }

        // OA 合同已交付金额（含税）：delivery_date 落在年内且不超过今天，归并到根项目按月累计
        LocalDate today = LocalDate.now();
        for (RevenueContractEntry entry : contractEntryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .eq(RevenueContractEntry::getPending, 0)
                .in(RevenueContractEntry::getBizLineId, managedIds)
                .isNotNull(RevenueContractEntry::getDeliveryDate)
                .ge(RevenueContractEntry::getDeliveryDate, LocalDate.of(year, 1, 1))
                .lt(RevenueContractEntry::getDeliveryDate, LocalDate.of(year + 1, 1, 1)))) {
            if (entry.getReceivableAmount() == null || entry.getDeliveryDate().isAfter(today)) {
                continue;
            }
            Long root = ds.rootOf(entry.getProjectId());
            if (root == null) {
                continue; // 未指定项目/无法归桶：不进项目行，由差额行暴露
            }
            ds.contractInclTax.computeIfAbsent(entry.getDeliveryDate().format(MONTH_FMT), k -> new HashMap<>())
                    .merge(root, entry.getReceivableAmount(), BigDecimal::add);
        }
        return ds;
    }

    private List<ProjectProfitReportVO.LineOption> buildLineOptions(Dataset ds) {
        List<ProjectProfitReportVO.LineOption> options = new ArrayList<>();
        for (BusinessLine line : ds.managedLines) {
            ProjectProfitReportVO.LineOption option = new ProjectProfitReportVO.LineOption();
            option.setBusinessLineId(line.getId());
            option.setBusinessLineName(line.getName());
            List<ProjectProfitReportVO.ProjectOption> projects = new ArrayList<>();
            if (MODE_FULL.equals(modeOf(line))) {
                for (Project root : ds.rootProjects(line.getId())) {
                    ProjectProfitReportVO.ProjectOption p = new ProjectProfitReportVO.ProjectOption();
                    p.setProjectId(root.getId());
                    p.setProjectName(root.getName());
                    projects.add(p);
                }
            }
            option.setProjects(projects);
            options.add(option);
        }
        return options;
    }

    // ==================== 行计算小工具 ====================

    private ProjectProfitReportVO.Row newRow(String rowType, String category, Long projectId,
                                             String projectName, boolean editable) {
        ProjectProfitReportVO.Row row = new ProjectProfitReportVO.Row();
        row.setRowType(rowType);
        row.setCategory(category);
        row.setProjectId(projectId);
        row.setProjectName(projectName);
        row.setEditable(editable);
        row.setRevenue(BigDecimal.ZERO);
        row.setSmsCost(BigDecimal.ZERO);
        row.setDirectCost(BigDecimal.ZERO);
        row.setPlatformFee(BigDecimal.ZERO);
        row.setCompensation(BigDecimal.ZERO);
        row.setOutsourcing(BigDecimal.ZERO);
        row.setSoftwareGift(BigDecimal.ZERO);
        row.setHours(BigDecimal.ZERO);
        row.setCost(BigDecimal.ZERO);
        return row;
    }

    private ProjectProfitReportVO.Row salesRow(BigDecimal hours, BigDecimal cost) {
        ProjectProfitReportVO.Row row = newRow(ROW_SALES, "销售", null, "销售", false);
        row.setHours(hours);
        row.setCost(cost);
        finalizeRow(row);
        return row;
    }

    /** 合计行：整行取镜像值（hours ← total_hours，cost ← labor_cost_1），毛利/率按公式重算 */
    private ProjectProfitReportVO.Row mirrorTotal(MirrorAcc mirror) {
        ProjectProfitReportVO.Row total = newRow(ROW_TOTAL, null, null, "合计", false);
        total.setRevenue(nz(mirror.revenue));
        total.setSmsCost(nz(mirror.smsCost));
        total.setDirectCost(nz(mirror.directCost));
        total.setPlatformFee(nz(mirror.platformFee));
        total.setCompensation(nz(mirror.compensation));
        total.setOutsourcing(nz(mirror.outsourcing));
        total.setSoftwareGift(nz(mirror.softwareGift));
        total.setHours(nz(mirror.totalHours));
        total.setCost(nz(mirror.laborCost1));
        finalizeRow(total);
        return total;
    }

    /** aggregate/simple 行：营收与 6 成本列继承镜像线级值（工时/成本仍来自明细） */
    private void applyMirror(ProjectProfitReportVO.Row row, MirrorAcc mirror) {
        row.setRevenue(nz(mirror.revenue));
        row.setSmsCost(nz(mirror.smsCost));
        row.setDirectCost(nz(mirror.directCost));
        row.setPlatformFee(nz(mirror.platformFee));
        row.setCompensation(nz(mirror.compensation));
        row.setOutsourcing(nz(mirror.outsourcing));
        row.setSoftwareGift(nz(mirror.softwareGift));
    }

    private void applyAllocations(ProjectProfitReportVO.Row row, Map<String, BigDecimal> byType) {
        byType.forEach((costType, amount) -> {
            switch (costType) {
                case "sms" -> row.setSmsCost(row.getSmsCost().add(amount));
                case "direct" -> row.setDirectCost(row.getDirectCost().add(amount));
                case "platform_fee" -> row.setPlatformFee(row.getPlatformFee().add(amount));
                case "compensation" -> row.setCompensation(row.getCompensation().add(amount));
                case "outsourcing" -> row.setOutsourcing(row.getOutsourcing().add(amount));
                case "software_gift" -> row.setSoftwareGift(row.getSoftwareGift().add(amount));
                default -> { /* COST_TYPES 之外忽略 */ }
            }
        });
    }

    private void addLabor(ProjectProfitReportVO.Row row, RevenueCostEntry entry) {
        row.setHours(row.getHours().add(nz(entry.getHours())));
        row.setCost(row.getCost().add(nz(entry.getCostAmount())));
    }

    /** 考核毛利 = 营收 − 6 成本列 − 人工成本；率 = 毛利/营收×100（营收为 0 → null） */
    private void finalizeRow(ProjectProfitReportVO.Row row) {
        BigDecimal gross = nz(row.getRevenue())
                .subtract(nz(row.getSmsCost()))
                .subtract(nz(row.getDirectCost()))
                .subtract(nz(row.getPlatformFee()))
                .subtract(nz(row.getCompensation()))
                .subtract(nz(row.getOutsourcing()))
                .subtract(nz(row.getSoftwareGift()))
                .subtract(nz(row.getCost()));
        row.setGrossProfit(gross);
        row.setGrossProfitRate(rate(gross, row.getRevenue()));
    }

    private void mergeRow(Map<String, ProjectProfitReportVO.Row> merged, ProjectProfitReportVO.Row row) {
        String key = row.getRowType() + ":" + (row.getProjectId() == null ? row.getProjectName() : row.getProjectId());
        merged.computeIfAbsent(key, k -> newRow(row.getRowType(), row.getCategory(), row.getProjectId(),
                row.getProjectName(), Boolean.TRUE.equals(row.getEditable())));
        ProjectProfitReportVO.Row acc = merged.get(key);
        acc.setRevenue(acc.getRevenue().add(nz(row.getRevenue())));
        acc.setSmsCost(acc.getSmsCost().add(nz(row.getSmsCost())));
        acc.setDirectCost(acc.getDirectCost().add(nz(row.getDirectCost())));
        acc.setPlatformFee(acc.getPlatformFee().add(nz(row.getPlatformFee())));
        acc.setCompensation(acc.getCompensation().add(nz(row.getCompensation())));
        acc.setOutsourcing(acc.getOutsourcing().add(nz(row.getOutsourcing())));
        acc.setSoftwareGift(acc.getSoftwareGift().add(nz(row.getSoftwareGift())));
        acc.setHours(acc.getHours().add(nz(row.getHours())));
        acc.setCost(acc.getCost().add(nz(row.getCost())));
    }

    private void addInto(ProjectProfitReportVO.Row acc, ProjectProfitReportVO.Row row) {
        acc.setRevenue(acc.getRevenue().add(nz(row.getRevenue())));
        acc.setSmsCost(acc.getSmsCost().add(nz(row.getSmsCost())));
        acc.setDirectCost(acc.getDirectCost().add(nz(row.getDirectCost())));
        acc.setPlatformFee(acc.getPlatformFee().add(nz(row.getPlatformFee())));
        acc.setCompensation(acc.getCompensation().add(nz(row.getCompensation())));
        acc.setOutsourcing(acc.getOutsourcing().add(nz(row.getOutsourcing())));
        acc.setSoftwareGift(acc.getSoftwareGift().add(nz(row.getSoftwareGift())));
        acc.setHours(acc.getHours().add(nz(row.getHours())));
        acc.setCost(acc.getCost().add(nz(row.getCost())));
    }

    /** acc -= row（数值列） */
    private void subtract(ProjectProfitReportVO.Row acc, ProjectProfitReportVO.Row row) {
        acc.setRevenue(acc.getRevenue().subtract(nz(row.getRevenue())));
        acc.setSmsCost(acc.getSmsCost().subtract(nz(row.getSmsCost())));
        acc.setDirectCost(acc.getDirectCost().subtract(nz(row.getDirectCost())));
        acc.setPlatformFee(acc.getPlatformFee().subtract(nz(row.getPlatformFee())));
        acc.setCompensation(acc.getCompensation().subtract(nz(row.getCompensation())));
        acc.setOutsourcing(acc.getOutsourcing().subtract(nz(row.getOutsourcing())));
        acc.setSoftwareGift(acc.getSoftwareGift().subtract(nz(row.getSoftwareGift())));
        acc.setHours(acc.getHours().subtract(nz(row.getHours())));
        acc.setCost(acc.getCost().subtract(nz(row.getCost())));
    }

    /** 含税 → 未税：÷(1+taxRate/100)，保留 2 位 HALF_UP（与「交付与利润」excludeTax 同口径） */
    private BigDecimal exTax(BigDecimal inclTaxAmount, BigDecimal divisor) {
        if (inclTaxAmount == null) {
            return BigDecimal.ZERO;
        }
        return inclTaxAmount.divide(divisor, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal part, BigDecimal total) {
        if (part == null || total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isSales(String workType) {
        return "sales".equals(workType);
    }

    private String modeOf(BusinessLine line) {
        return StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : MODE_FULL;
    }

    private List<String> normalizePeriods(List<String> periods) {
        if (periods == null || periods.isEmpty()) {
            return List.of();
        }
        Set<String> requested = periods.stream()
                .filter(Objects::nonNull)
                .map(p -> p.trim().toUpperCase())
                .collect(Collectors.toSet());
        return PERIOD_ORDER.stream().filter(requested::contains).toList();
    }

    private List<String> periodMonths(int year, String period) {
        int from = "H2".equals(period) ? 7 : 1;
        int to = "H1".equals(period) ? 6 : 12;
        List<String> months = new ArrayList<>();
        for (int m = from; m <= to; m++) {
            months.add(String.format("%d-%02d", year, m));
        }
        return months;
    }

    /** 复制自 RevenueDeliverySummaryService.rootIdOf：沿 parentId 走到根项目 */
    private Long rootIdOf(Long projectId, Map<Long, Project> projectsById) {
        Project project = projectsById.get(projectId);
        while (project != null && project.getParentId() != null) {
            project = projectsById.get(project.getParentId());
        }
        return project == null ? null : project.getId();
    }

    /** 复制自 RevenueDeliverySummaryService.aliasMap：别名源项目 → 同业务线目标主项目 */
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

    // ==================== 内部结构 ====================

    private static final class Filters {
        static final Filters NONE = new Filters(Set.of(), Set.of(), Set.of());
        final Set<Long> businessLineIds;
        final Set<String> categories;
        final Set<Long> projectIds;

        Filters(Set<Long> businessLineIds, Set<String> categories, Set<Long> projectIds) {
            this.businessLineIds = businessLineIds;
            this.categories = categories;
            this.projectIds = projectIds;
        }
    }

    /** 镜像累计器：同 (月, 本系统业务线) 多条工时系统行求和 */
    private static final class MirrorAcc {
        BigDecimal revenue, smsCost, directCost, platformFee, compensation, outsourcing, softwareGift;
        BigDecimal totalHours, laborCost1;

        void add(BizLineProfitReport row) {
            revenue = plus(revenue, row.getRevenue());
            smsCost = plus(smsCost, row.getSmsCost());
            directCost = plus(directCost, row.getDirectCost());
            platformFee = plus(platformFee, row.getPlatformFee());
            compensation = plus(compensation, row.getCompensation());
            outsourcing = plus(outsourcing, row.getOutsourcing());
            softwareGift = plus(softwareGift, row.getSoftwareGift());
            totalHours = plus(totalHours, row.getTotalHours());
            laborCost1 = plus(laborCost1, row.getLaborCost1());
        }

        void add(MirrorAcc other) {
            revenue = plus(revenue, other.revenue);
            smsCost = plus(smsCost, other.smsCost);
            directCost = plus(directCost, other.directCost);
            platformFee = plus(platformFee, other.platformFee);
            compensation = plus(compensation, other.compensation);
            outsourcing = plus(outsourcing, other.outsourcing);
            softwareGift = plus(softwareGift, other.softwareGift);
            totalHours = plus(totalHours, other.totalHours);
            laborCost1 = plus(laborCost1, other.laborCost1);
        }

        private BigDecimal plus(BigDecimal a, BigDecimal b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.add(b);
        }
    }

    private final class Dataset {
        List<BusinessLine> managedLines = List.of();
        /** 月 → 业务线ID → 镜像累计 */
        Map<String, Map<Long, MirrorAcc>> mirror = new HashMap<>();
        List<String> availableMonths = List.of();
        LocalDateTime lastSyncedAt;
        /** 月 → 业务线ID → 成本明细行 */
        Map<String, Map<Long, List<RevenueCostEntry>>> costs = new HashMap<>();
        /** 月 → 根项目ID → OA 已交付含税金额 */
        Map<String, Map<Long, BigDecimal>> contractInclTax = new HashMap<>();
        /** 月 → 项目ID → (成本类型 → 金额) */
        Map<String, Map<Long, Map<String, BigDecimal>>> allocations = new HashMap<>();
        Map<Long, Project> projectsById = Map.of();
        Map<Long, Long> aliasToRoot = Map.of();
        /** 业务线ID → 根项目（别名源已排除，按 ID 升序） */
        Map<Long, List<Project>> rootProjects = new HashMap<>();
        Map<Long, BigDecimal> taxDivisor = new HashMap<>();


        MirrorAcc mirror(String yearMonth, Long lineId) {
            return mirror.getOrDefault(yearMonth, Map.of()).get(lineId);
        }

        List<RevenueCostEntry> costs(String yearMonth, Long lineId) {
            return costs.getOrDefault(yearMonth, Map.of()).getOrDefault(lineId, List.of());
        }

        BigDecimal contractInclTax(String yearMonth, Long rootProjectId) {
            return contractInclTax.getOrDefault(yearMonth, Map.of()).get(rootProjectId);
        }

        Map<String, BigDecimal> allocations(String yearMonth, Long projectId) {
            return allocations.getOrDefault(yearMonth, Map.of()).getOrDefault(projectId, Map.of());
        }

        List<Project> rootProjects(Long lineId) {
            return rootProjects.getOrDefault(lineId, List.of());
        }

        BigDecimal taxDivisor(Long lineId) {
            return taxDivisor.getOrDefault(lineId, BigDecimal.ONE);
        }

        /** 归并到根项目（含别名归并）；项目缺失/不属 managed 线返回 null */
        Long rootOf(Long projectId) {
            if (projectId == null) {
                return null;
            }
            Long root = rootIdOf(projectId, projectsById);
            return root == null ? null : aliasToRoot.getOrDefault(root, root);
        }
    }
}
