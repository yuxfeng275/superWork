package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueManualEntryDTO;
import com.bu.management.dto.RevenueSummaryVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueManualEntry;
import com.bu.management.entity.RevenueMonthlyCost;
import com.bu.management.entity.RevenueMonthlyIncome;
import com.bu.management.entity.RevenueProjectMapping;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueManualEntryMapper;
import com.bu.management.mapper.RevenueMonthlyCostMapper;
import com.bu.management.mapper.RevenueMonthlyIncomeMapper;
import com.bu.management.mapper.RevenueProjectMappingMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RevenueService {
    private static final String COST_SOURCE = "cost_project";
    private static final String INCOME_SOURCE = "contract_brand";
    private static final String H2_ESTIMATE = "h2_estimate";
    private static final long MAX_IMPORT_SIZE = 20L * 1024 * 1024;
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})\\D+(\\d{1,2})");
    private static final Set<String> MANUAL_ENTRY_TYPES = Set.of(
            H2_ESTIMATE, "partner_cost", "server_cost", "other_cost");

    private final RevenueProjectMappingMapper mappingMapper;
    private final RevenueMonthlyCostMapper costMapper;
    private final RevenueMonthlyIncomeMapper incomeMapper;
    private final RevenueManualEntryMapper manualEntryMapper;
    private final ProjectMapper projectMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ReentrantLock upsertLock = new ReentrantLock();

    @Transactional
    public RevenueImportResultVO importCostExcel(MultipartFile file) {
        validateImportFile(file);
        RevenueImportResultVO result = new RevenueImportResultVO();
        ImportReferences references = loadImportReferences();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("成本分析-项目视角");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter, evaluator)) {
                    continue;
                }
                try {
                    String month = readMonth(row.getCell(0), formatter, evaluator);
                    String sourceName = readRequiredText(row.getCell(2), formatter, evaluator, "项目名");
                    BigDecimal workHours = readDecimal(row.getCell(5), formatter, evaluator, "工时");
                    long workCost = readDecimal(row.getCell(6), formatter, evaluator, "工时成本")
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    RevenueProjectMapping mapping = findMapping(COST_SOURCE, sourceName);
                    if (mapping == null) {
                        mapping = createCostMapping(sourceName, references);
                        mappingMapper.insert(mapping);
                        incrementNewMapping(result);
                    }
                    Long businessLineId = resolveBusinessLineId(mapping, references.projectsById());
                    if (businessLineId == null) {
                        incrementPending(result);
                        result.getErrors().add(rowError(rowIndex, "项目映射尚未关联项目或业务线"));
                        continue;
                    }
                    upsertCost(month, mapping.getProjectId(), businessLineId,
                            defaultCategory(mapping.getCategory()), workHours, workCost);
                    incrementSuccess(result);
                } catch (RuntimeException exception) {
                    result.getErrors().add(rowError(rowIndex, exception.getMessage()));
                }
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析成本 Excel: " + safeMessage(exception), exception);
        }
    }

    @Transactional
    public RevenueImportResultVO importIncomeExcel(MultipartFile file) {
        validateImportFile(file);
        RevenueImportResultVO result = new RevenueImportResultVO();
        ImportReferences references = loadImportReferences();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheet("合同明细");
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> headers = readHeaders(sheet.getRow(sheet.getFirstRowNum()), formatter, evaluator);
            int monthColumn = requireHeader(headers, "收款销售月份");
            int brandColumn = requireHeader(headers, "品牌");
            int typeColumn = requireHeader(headers, "收款款项类型");
            int receivableColumn = requireHeader(headers, "应收金额");
            int receivedColumn = requireHeader(headers, "实收金额");
            Map<IncomeKey, IncomeAggregate> aggregates = new LinkedHashMap<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, formatter, evaluator)) {
                    continue;
                }
                try {
                    String month = readMonth(row.getCell(monthColumn), formatter, evaluator);
                    String type = readRequiredText(row.getCell(typeColumn), formatter, evaluator, "收款款项类型");
                    String brand = readText(row.getCell(brandColumn), formatter, evaluator);
                    if (!type.contains("会员通") && brand.isBlank()) {
                        throw new IllegalArgumentException("品牌不能为空");
                    }
                    long receivable = readDecimal(row.getCell(receivableColumn), formatter, evaluator, "应收金额")
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    long received = readDecimal(row.getCell(receivedColumn), formatter, evaluator, "实收金额")
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    Assignment assignment = resolveIncomeAssignment(brand, type, references, result);
                    if (assignment.businessLineId() == null) {
                        incrementPending(result);
                        result.getErrors().add(rowError(rowIndex, "品牌映射尚未关联项目或业务线"));
                        continue;
                    }
                    IncomeKey key = new IncomeKey(month, assignment.projectId(), assignment.businessLineId());
                    aggregates.computeIfAbsent(key, ignored -> new IncomeAggregate())
                            .add(receivable, received);
                    incrementSuccess(result);
                } catch (RuntimeException exception) {
                    result.getErrors().add(rowError(rowIndex, exception.getMessage()));
                }
            }
            aggregates.forEach(this::upsertIncome);
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析营收 Excel: " + safeMessage(exception), exception);
        }
    }

    public RevenueSummaryVO getSummary(int year) {
        String yearPrefix = year + "-";
        List<RevenueMonthlyCost> costs = nullSafe(costMapper.selectList(
                new LambdaQueryWrapper<RevenueMonthlyCost>()
                        .likeRight(RevenueMonthlyCost::getYearMonth, yearPrefix)));
        List<RevenueMonthlyIncome> incomes = nullSafe(incomeMapper.selectList(
                new LambdaQueryWrapper<RevenueMonthlyIncome>()
                        .likeRight(RevenueMonthlyIncome::getYearMonth, yearPrefix)));
        List<RevenueManualEntry> manualEntries = nullSafe(manualEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueManualEntry>()
                        .likeRight(RevenueManualEntry::getYearMonth, yearPrefix)));
        List<Project> projects = nullSafe(projectMapper.selectList(new LambdaQueryWrapper<>()));
        List<BusinessLine> businessLines = nullSafe(businessLineMapper.selectList(new LambdaQueryWrapper<>()));
        Map<Long, Project> projectsById = projects.stream()
                .filter(project -> project.getId() != null)
                .collect(Collectors.toMap(Project::getId, project -> project, (first, second) -> first));

        RevenueSummaryVO summary = new RevenueSummaryVO();
        summary.setYear(year);
        long totalReceivable = incomes.stream().mapToLong(item -> value(item.getReceivableAmount())).sum();
        long importedCost = costs.stream().mapToLong(item -> value(item.getWorkCost())).sum();
        long manualCost = manualEntries.stream().filter(this::isActualCost)
                .mapToLong(item -> value(item.getAmount())).sum();
        long totalCost = importedCost + manualCost;
        summary.setTotalReceivable(totalReceivable);
        summary.setTotalCost(totalCost);
        summary.setTotalProfit(totalReceivable - totalCost);
        summary.setProfitRate(profitRate(totalReceivable - totalCost, totalReceivable));
        summary.setMonthlyTrend(buildMonthlyTrend(costs, incomes, manualEntries));
        summary.setBusinessLines(businessLines.stream()
                .sorted(Comparator.comparing(BusinessLine::getId, Comparator.nullsLast(Long::compareTo)))
                .map(line -> buildBusinessLineSummary(line, projects, projectsById, costs, incomes, manualEntries))
                .toList());
        return summary;
    }

    public List<RevenueProjectMapping> listMappings(String sourceType) {
        LambdaQueryWrapper<RevenueProjectMapping> query = new LambdaQueryWrapper<RevenueProjectMapping>()
                .orderByAsc(RevenueProjectMapping::getSourceType)
                .orderByAsc(RevenueProjectMapping::getSourceName);
        if (sourceType != null && !sourceType.isBlank()) {
            query.eq(RevenueProjectMapping::getSourceType, sourceType.trim());
        }
        return nullSafe(mappingMapper.selectList(query));
    }

    @Transactional
    public RevenueProjectMapping updateMapping(Long id, RevenueProjectMapping request) {
        if (id == null || request == null) {
            throw new IllegalArgumentException("映射 ID 和请求内容不能为空");
        }
        RevenueProjectMapping existing = mappingMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("映射不存在");
        }
        validateCategory(request.getCategory());
        existing.setProjectId(request.getProjectId());
        existing.setBusinessLineId(request.getBusinessLineId());
        existing.setCategory(request.getCategory());
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        mappingMapper.updateById(existing);
        return existing;
    }

    public List<RevenueManualEntryDTO> listManualEntries(Integer year, String month) {
        LambdaQueryWrapper<RevenueManualEntry> query = new LambdaQueryWrapper<RevenueManualEntry>()
                .orderByDesc(RevenueManualEntry::getYearMonth)
                .orderByDesc(RevenueManualEntry::getId);
        if (month != null && !month.isBlank()) {
            query.eq(RevenueManualEntry::getYearMonth, normalizeMonth(month));
        } else if (year != null) {
            query.likeRight(RevenueManualEntry::getYearMonth, year + "-");
        }
        return nullSafe(manualEntryMapper.selectList(query)).stream().map(this::toDto).toList();
    }

    @Transactional
    public RevenueManualEntryDTO createManualEntry(RevenueManualEntryDTO request, Long userId) {
        validateManualEntry(request);
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        RevenueManualEntry entry = toEntity(request);
        entry.setId(null);
        entry.setCreatedBy(userId);
        manualEntryMapper.insert(entry);
        return toDto(entry);
    }

    @Transactional
    public RevenueManualEntryDTO updateManualEntry(Long id, RevenueManualEntryDTO request) {
        validateManualEntry(request);
        if (id == null) {
            throw new IllegalArgumentException("手动维护项 ID 不能为空");
        }
        RevenueManualEntry existing = manualEntryMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("手动维护项不存在");
        }
        existing.setYearMonth(normalizeMonth(request.getYearMonth()));
        existing.setProjectId(request.getProjectId());
        existing.setBusinessLineId(request.getBusinessLineId());
        existing.setEntryType(request.getEntryType());
        existing.setAmount(request.getAmount());
        existing.setRemark(request.getRemark());
        manualEntryMapper.updateById(existing);
        return toDto(existing);
    }

    @Transactional
    public void deleteManualEntry(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("手动维护项 ID 不能为空");
        }
        manualEntryMapper.deleteById(id);
    }

    private ImportReferences loadImportReferences() {
        List<Project> projects = nullSafe(projectMapper.selectList(new LambdaQueryWrapper<>()));
        List<BusinessLine> lines = nullSafe(businessLineMapper.selectList(new LambdaQueryWrapper<>()));
        Map<Long, Project> byId = projects.stream()
                .filter(project -> project.getId() != null)
                .collect(Collectors.toMap(Project::getId, project -> project, (first, second) -> first));
        return new ImportReferences(projects, lines, byId);
    }

    private RevenueProjectMapping findMapping(String sourceType, String sourceName) {
        return mappingMapper.selectOne(new LambdaQueryWrapper<RevenueProjectMapping>()
                .eq(RevenueProjectMapping::getSourceType, sourceType)
                .eq(RevenueProjectMapping::getSourceName, sourceName)
                .eq(RevenueProjectMapping::getStatus, 1));
    }

    private RevenueProjectMapping createCostMapping(String sourceName, ImportReferences references) {
        RevenueProjectMapping mapping = baseMapping(COST_SOURCE, sourceName, categoryFor(sourceName));
        String normalized = normalize(sourceName);
        if (normalized.contains("会员通")) {
            mapping.setBusinessLineId(findBusinessLineId(references.businessLines(), "会员通"));
        } else if (normalized.contains("全域云鹿saas")) {
            mapping.setBusinessLineId(findBusinessLineId(references.businessLines(), "saas"));
        } else if (normalized.contains("全域云鹿定制") && normalized.contains("【销售】")) {
            mapping.setBusinessLineId(findBusinessLineId(references.businessLines(), "定制"));
        } else if (normalized.contains("全域私域精准")) {
            mapping.setBusinessLineId(findBusinessLineId(references.businessLines(), "精准"));
        } else {
            Project project = findKnownProject(sourceName, references.projects());
            if (project != null) {
                mapping.setProjectId(project.getId());
            }
        }
        return mapping;
    }

    private Assignment resolveIncomeAssignment(String brand, String type, ImportReferences references,
                                                RevenueImportResultVO result) {
        if (type.contains("会员通")) {
            return new Assignment(null, findBusinessLineId(references.businessLines(), "会员通"));
        }
        RevenueProjectMapping mapping = findMapping(INCOME_SOURCE, brand);
        if (mapping == null) {
            mapping = baseMapping(INCOME_SOURCE, brand, "delivery");
            Project project = findKnownProject(brand, references.projects());
            if (project != null) {
                mapping.setProjectId(project.getId());
            } else {
                mapping.setBusinessLineId(findBusinessLineId(references.businessLines(), "定制"));
            }
            mappingMapper.insert(mapping);
            incrementNewMapping(result);
        }
        return new Assignment(mapping.getProjectId(), resolveBusinessLineId(mapping, references.projectsById()));
    }

    private RevenueProjectMapping baseMapping(String sourceType, String sourceName, String category) {
        RevenueProjectMapping mapping = new RevenueProjectMapping();
        mapping.setSourceType(sourceType);
        mapping.setSourceName(sourceName);
        mapping.setCategory(category);
        mapping.setStatus(1);
        return mapping;
    }

    private Project findKnownProject(String sourceName, List<Project> projects) {
        String normalizedSource = normalize(sourceName);
        Map<String, String> keywordTargets = new LinkedHashMap<>();
        keywordTargets.put("皇家", "皇家");
        keywordTargets.put("speedo", "speedo");
        keywordTargets.put("澳优", "澳优");
        keywordTargets.put("飞鹤", "飞鹤");
        keywordTargets.put("逢时", "逢时");
        keywordTargets.put("黄天鹅", "黄天鹅");
        keywordTargets.put("海普诺凯", "海普诺凯");
        for (Map.Entry<String, String> rule : keywordTargets.entrySet()) {
            if (normalizedSource.contains(rule.getKey())) {
                return projects.stream()
                        .filter(project -> normalize(project.getName()).contains(rule.getValue()))
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    private Long findBusinessLineId(List<BusinessLine> lines, String keyword) {
        String normalizedKeyword = normalize(keyword);
        return lines.stream()
                .filter(line -> normalize(line.getName()).contains(normalizedKeyword))
                .map(BusinessLine::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Long resolveBusinessLineId(RevenueProjectMapping mapping, Map<Long, Project> projectsById) {
        if (mapping.getBusinessLineId() != null) {
            return mapping.getBusinessLineId();
        }
        Project project = projectsById.get(mapping.getProjectId());
        return project == null ? null : project.getBusinessLineId();
    }

    private void upsertCost(String month, Long projectId, Long businessLineId, String category,
                            BigDecimal workHours, long workCost) {
        upsertLock.lock();
        try {
            LambdaQueryWrapper<RevenueMonthlyCost> query = new LambdaQueryWrapper<RevenueMonthlyCost>()
                    .eq(RevenueMonthlyCost::getYearMonth, month)
                    .eq(RevenueMonthlyCost::getCategory, category);
            if (projectId == null) {
                query.isNull(RevenueMonthlyCost::getProjectId)
                        .eq(RevenueMonthlyCost::getBusinessLineId, businessLineId);
            } else {
                query.eq(RevenueMonthlyCost::getProjectId, projectId);
            }
            RevenueMonthlyCost existing = costMapper.selectOne(query);
            if (existing == null) {
                existing = new RevenueMonthlyCost();
                existing.setYearMonth(month);
                existing.setProjectId(projectId);
                existing.setBusinessLineId(businessLineId);
                existing.setCategory(category);
                existing.setWorkHours(workHours);
                existing.setWorkCost(workCost);
                costMapper.insert(existing);
            } else {
                existing.setBusinessLineId(businessLineId);
                existing.setWorkHours(workHours);
                existing.setWorkCost(workCost);
                costMapper.updateById(existing);
            }
        } finally {
            upsertLock.unlock();
        }
    }

    private void upsertIncome(IncomeKey key, IncomeAggregate aggregate) {
        upsertLock.lock();
        try {
            LambdaQueryWrapper<RevenueMonthlyIncome> query = new LambdaQueryWrapper<RevenueMonthlyIncome>()
                    .eq(RevenueMonthlyIncome::getYearMonth, key.month());
            if (key.projectId() == null) {
                query.isNull(RevenueMonthlyIncome::getProjectId)
                        .eq(RevenueMonthlyIncome::getBusinessLineId, key.businessLineId());
            } else {
                query.eq(RevenueMonthlyIncome::getProjectId, key.projectId());
            }
            RevenueMonthlyIncome existing = incomeMapper.selectOne(query);
            if (existing == null) {
                existing = new RevenueMonthlyIncome();
                existing.setYearMonth(key.month());
                existing.setProjectId(key.projectId());
                existing.setBusinessLineId(key.businessLineId());
                existing.setContractCount(aggregate.contractCount);
                existing.setReceivableAmount(aggregate.receivable);
                existing.setReceivedAmount(aggregate.received);
                incomeMapper.insert(existing);
            } else {
                existing.setBusinessLineId(key.businessLineId());
                existing.setContractCount(aggregate.contractCount);
                existing.setReceivableAmount(aggregate.receivable);
                existing.setReceivedAmount(aggregate.received);
                incomeMapper.updateById(existing);
            }
        } finally {
            upsertLock.unlock();
        }
    }

    private RevenueSummaryVO.BusinessLineSummary buildBusinessLineSummary(
            BusinessLine line, List<Project> projects, Map<Long, Project> projectsById,
            List<RevenueMonthlyCost> allCosts, List<RevenueMonthlyIncome> allIncomes,
            List<RevenueManualEntry> allManualEntries) {
        List<RevenueMonthlyCost> costs = allCosts.stream()
                .filter(item -> Objects.equals(line.getId(), item.getBusinessLineId())).toList();
        List<RevenueMonthlyIncome> incomes = allIncomes.stream()
                .filter(item -> Objects.equals(line.getId(), item.getBusinessLineId())).toList();
        List<RevenueManualEntry> manualEntries = allManualEntries.stream()
                .filter(item -> Objects.equals(line.getId(), item.getBusinessLineId())).toList();
        long receivable = incomes.stream().mapToLong(item -> value(item.getReceivableAmount())).sum();
        long totalCost = costs.stream().mapToLong(item -> value(item.getWorkCost())).sum()
                + manualEntries.stream().filter(this::isActualCost).mapToLong(item -> value(item.getAmount())).sum();

        RevenueSummaryVO.BusinessLineSummary summary = new RevenueSummaryVO.BusinessLineSummary();
        summary.setBusinessLineId(line.getId());
        summary.setBusinessLineName(line.getName());
        boolean memberLine = normalize(line.getName()).contains("会员通");
        summary.setType(memberLine ? "business_line_summary" : "project_breakdown");
        summary.setTotalReceivable(receivable);
        summary.setTotalCost(totalCost);
        summary.setTotalProfit(receivable - totalCost);
        summary.setProfitRate(profitRate(receivable - totalCost, receivable));
        summary.setMonths(buildMonthlyData(costs, incomes, manualEntries, false));
        if (memberLine) {
            summary.setProjects(null);
        } else {
            summary.setProjects(buildProjectSummaries(line.getId(), projects, projectsById,
                    costs, incomes, manualEntries));
        }
        return summary;
    }

    private List<RevenueSummaryVO.ProjectSummary> buildProjectSummaries(
            Long businessLineId, List<Project> projects, Map<Long, Project> projectsById,
            List<RevenueMonthlyCost> costs, List<RevenueMonthlyIncome> incomes,
            List<RevenueManualEntry> manualEntries) {
        Set<Long> projectIds = new LinkedHashSet<>();
        projects.stream()
                .filter(project -> Objects.equals(businessLineId, project.getBusinessLineId()))
                .map(Project::getId)
                .filter(Objects::nonNull)
                .filter(id -> hasProjectData(id, costs, incomes, manualEntries))
                .forEach(projectIds::add);
        costs.stream().map(RevenueMonthlyCost::getProjectId).filter(Objects::nonNull).forEach(projectIds::add);
        incomes.stream().map(RevenueMonthlyIncome::getProjectId).filter(Objects::nonNull).forEach(projectIds::add);
        manualEntries.stream().map(RevenueManualEntry::getProjectId).filter(Objects::nonNull).forEach(projectIds::add);
        return projectIds.stream().map(projectId -> {
            List<RevenueMonthlyCost> projectCosts = costs.stream()
                    .filter(item -> Objects.equals(projectId, item.getProjectId())).toList();
            List<RevenueMonthlyIncome> projectIncomes = incomes.stream()
                    .filter(item -> Objects.equals(projectId, item.getProjectId())).toList();
            List<RevenueManualEntry> projectManual = manualEntries.stream()
                    .filter(item -> Objects.equals(projectId, item.getProjectId())).toList();
            return buildProjectSummary(projectId, projectsById.get(projectId), projectCosts,
                    projectIncomes, projectManual);
        }).toList();
    }

    private boolean hasProjectData(Long projectId, List<RevenueMonthlyCost> costs,
                                   List<RevenueMonthlyIncome> incomes,
                                   List<RevenueManualEntry> manualEntries) {
        return costs.stream().anyMatch(item -> Objects.equals(projectId, item.getProjectId()))
                || incomes.stream().anyMatch(item -> Objects.equals(projectId, item.getProjectId()))
                || manualEntries.stream().anyMatch(item -> Objects.equals(projectId, item.getProjectId()));
    }

    private RevenueSummaryVO.ProjectSummary buildProjectSummary(
            Long projectId, Project project, List<RevenueMonthlyCost> costs,
            List<RevenueMonthlyIncome> incomes, List<RevenueManualEntry> manualEntries) {
        long receivable = incomes.stream().mapToLong(item -> value(item.getReceivableAmount())).sum();
        long deliveryCost = costs.stream().filter(item -> !"sales".equals(item.getCategory()))
                .mapToLong(item -> value(item.getWorkCost())).sum();
        long salesCost = costs.stream().filter(item -> "sales".equals(item.getCategory()))
                .mapToLong(item -> value(item.getWorkCost())).sum();
        BigDecimal deliveryHours = costs.stream().filter(item -> !"sales".equals(item.getCategory()))
                .map(item -> decimalValue(item.getWorkHours())).reduce(BigDecimal.ZERO, BigDecimal::add);
        long partnerCost = manualAmount(manualEntries, "partner_cost");
        long serverCost = manualAmount(manualEntries, "server_cost");
        long otherCost = manualAmount(manualEntries, "other_cost");
        long totalCost = deliveryCost + salesCost + partnerCost + serverCost + otherCost;
        long h2Estimate = manualAmount(manualEntries, H2_ESTIMATE);

        RevenueSummaryVO.ProjectSummary summary = new RevenueSummaryVO.ProjectSummary();
        summary.setProjectId(projectId);
        summary.setProjectName(project == null ? "项目 " + projectId : project.getName());
        summary.setReceivable(receivable);
        summary.setDeliveryHours(deliveryHours);
        summary.setDeliveryCost(deliveryCost);
        summary.setSalesCost(salesCost);
        summary.setPartnerCost(partnerCost);
        summary.setServerCost(serverCost);
        summary.setOtherCost(otherCost);
        summary.setTotalCost(totalCost);
        summary.setProfit(receivable - totalCost);
        summary.setProfitRate(profitRate(receivable - totalCost, receivable));
        summary.setH2Estimate(h2Estimate == 0 ? null : h2Estimate);
        summary.setMonths(buildMonthlyData(costs, incomes, manualEntries, true));
        return summary;
    }

    private List<RevenueSummaryVO.MonthlyTrendItem> buildMonthlyTrend(
            List<RevenueMonthlyCost> costs, List<RevenueMonthlyIncome> incomes,
            List<RevenueManualEntry> manualEntries) {
        Map<String, MonthTotals> months = collectMonths(costs, incomes, manualEntries, false);
        return months.entrySet().stream().map(entry -> {
            RevenueSummaryVO.MonthlyTrendItem item = new RevenueSummaryVO.MonthlyTrendItem();
            item.setMonth(entry.getKey());
            item.setIncome(entry.getValue().income);
            item.setCost(entry.getValue().cost);
            return item;
        }).toList();
    }

    private List<RevenueSummaryVO.MonthlyData> buildMonthlyData(
            List<RevenueMonthlyCost> costs, List<RevenueMonthlyIncome> incomes,
            List<RevenueManualEntry> manualEntries, boolean includeHours) {
        Map<String, MonthTotals> months = collectMonths(costs, incomes, manualEntries, includeHours);
        return months.entrySet().stream().map(entry -> {
            RevenueSummaryVO.MonthlyData item = new RevenueSummaryVO.MonthlyData();
            item.setMonth(entry.getKey());
            item.setIncome(entry.getValue().income);
            item.setCost(entry.getValue().cost);
            item.setHours(includeHours ? entry.getValue().hours : null);
            return item;
        }).toList();
    }

    private Map<String, MonthTotals> collectMonths(
            List<RevenueMonthlyCost> costs, List<RevenueMonthlyIncome> incomes,
            List<RevenueManualEntry> manualEntries, boolean includeHours) {
        Map<String, MonthTotals> months = new TreeMap<>();
        incomes.forEach(income -> months.computeIfAbsent(income.getYearMonth(), ignored -> new MonthTotals())
                .income += value(income.getReceivableAmount()));
        costs.forEach(cost -> {
            MonthTotals totals = months.computeIfAbsent(cost.getYearMonth(), ignored -> new MonthTotals());
            totals.cost += value(cost.getWorkCost());
            if (includeHours && !"sales".equals(cost.getCategory())) {
                totals.hours = totals.hours.add(decimalValue(cost.getWorkHours()));
            }
        });
        manualEntries.stream().filter(this::isActualCost).forEach(entry ->
                months.computeIfAbsent(entry.getYearMonth(), ignored -> new MonthTotals()).cost += value(entry.getAmount()));
        return months;
    }

    private long manualAmount(List<RevenueManualEntry> entries, String type) {
        return entries.stream().filter(entry -> type.equals(entry.getEntryType()))
                .mapToLong(entry -> value(entry.getAmount())).sum();
    }

    private boolean isActualCost(RevenueManualEntry entry) {
        return !H2_ESTIMATE.equals(entry.getEntryType());
    }

    private BigDecimal profitRate(long profit, long receivable) {
        if (receivable == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(profit)
                .divide(BigDecimal.valueOf(receivable), 4, RoundingMode.HALF_UP);
    }

    private void validateManualEntry(RevenueManualEntryDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("手动维护项不能为空");
        }
        if (!MANUAL_ENTRY_TYPES.contains(request.getEntryType())) {
            throw new IllegalArgumentException("entry_type 必须是 h2_estimate、partner_cost、server_cost 或 other_cost");
        }
        request.setYearMonth(normalizeMonth(request.getYearMonth()));
        if (request.getBusinessLineId() == null) {
            throw new IllegalArgumentException("business_line_id 不能为空");
        }
        if (request.getAmount() == null) {
            throw new IllegalArgumentException("amount 不能为空");
        }
    }

    private RevenueManualEntry toEntity(RevenueManualEntryDTO dto) {
        RevenueManualEntry entry = new RevenueManualEntry();
        entry.setId(dto.getId());
        entry.setYearMonth(dto.getYearMonth());
        entry.setProjectId(dto.getProjectId());
        entry.setBusinessLineId(dto.getBusinessLineId());
        entry.setEntryType(dto.getEntryType());
        entry.setAmount(dto.getAmount());
        entry.setRemark(dto.getRemark());
        return entry;
    }

    private RevenueManualEntryDTO toDto(RevenueManualEntry entry) {
        RevenueManualEntryDTO dto = new RevenueManualEntryDTO();
        dto.setId(entry.getId());
        dto.setYearMonth(entry.getYearMonth());
        dto.setProjectId(entry.getProjectId());
        dto.setBusinessLineId(entry.getBusinessLineId());
        dto.setEntryType(entry.getEntryType());
        dto.setAmount(entry.getAmount());
        dto.setRemark(entry.getRemark());
        return dto;
    }

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Excel 文件不能为空");
        }
        if (file.getSize() > MAX_IMPORT_SIZE) {
            throw new IllegalArgumentException("Excel 文件不能超过 20MB");
        }
        String filename = file.getOriginalFilename();
        if (filename != null && !filename.isBlank()) {
            String lower = filename.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                throw new IllegalArgumentException("仅支持 Excel 文件");
            }
        }
    }

    private Map<String, Integer> readHeaders(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            throw new IllegalArgumentException("Excel 缺少表头");
        }
        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : row) {
            String value = formatter.formatCellValue(cell, evaluator).trim();
            if (!value.isEmpty()) {
                headers.put(value, cell.getColumnIndex());
            }
        }
        return headers;
    }

    private int requireHeader(Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Excel 缺少列: " + name);
        }
        return index;
    }

    private String readRequiredText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, String field) {
        String value = readText(cell, formatter, evaluator);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private String readText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();
    }

    private BigDecimal readDecimal(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator, String field) {
        String value = readRequiredText(cell, formatter, evaluator, field)
                .replace(",", "").replace("¥", "").replace("￥", "").trim();
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "不是有效数字");
        }
    }

    private String readMonth(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                && DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            return YearMonth.from(dateTime).toString();
        }
        return normalizeMonth(readRequiredText(cell, formatter, evaluator, "月份"));
    }

    private String normalizeMonth(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("月份不能为空");
        }
        Matcher matcher = MONTH_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            throw new IllegalArgumentException("月份格式必须为 YYYY-MM");
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("月份格式必须为 YYYY-MM");
        }
        return String.format(Locale.ROOT, "%04d-%02d", year, month);
    }

    private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String categoryFor(String sourceName) {
        return sourceName.contains("【销售】") ? "sales" : "delivery";
    }

    private String defaultCategory(String category) {
        return category == null || category.isBlank() ? "delivery" : category;
    }

    private void validateCategory(String category) {
        if (!Set.of("delivery", "sales", "product").contains(category)) {
            throw new IllegalArgumentException("category 必须是 delivery、sales 或 product");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String rowError(int zeroBasedRowIndex, String message) {
        return "第 " + (zeroBasedRowIndex + 1) + " 行: " + (message == null ? "处理失败" : message);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? "文件格式错误" : exception.getMessage();
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal decimalValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void incrementSuccess(RevenueImportResultVO result) {
        result.setSuccessCount(result.getSuccessCount() + 1);
    }

    private void incrementNewMapping(RevenueImportResultVO result) {
        result.setNewMappingCount(result.getNewMappingCount() + 1);
    }

    private void incrementPending(RevenueImportResultVO result) {
        result.setPendingMappingCount(result.getPendingMappingCount() + 1);
    }

    private record ImportReferences(List<Project> projects, List<BusinessLine> businessLines,
                                    Map<Long, Project> projectsById) {
    }

    private record Assignment(Long projectId, Long businessLineId) {
    }

    private record IncomeKey(String month, Long projectId, Long businessLineId) {
    }

    private static class IncomeAggregate {
        private int contractCount;
        private long receivable;
        private long received;

        private IncomeAggregate add(long receivable, long received) {
            contractCount++;
            this.receivable += receivable;
            this.received += received;
            return this;
        }
    }

    private static class MonthTotals {
        private long income;
        private long cost;
        private BigDecimal hours = BigDecimal.ZERO;
    }
}
