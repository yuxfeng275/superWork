package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueContractMappingVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueContractImportBatchMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目交付合同明细导入与归属。
 * 先依据「收款款项类型」确定业务线，再仅在该业务线内按「品牌」匹配既有营收项目；禁止品牌跨业务线猜测。
 * 未命中项目时，aggregate/simple 业务线落业务线级行，full 业务线进入待映射清单；不静默丢弃。
 * 同一文件重复导入以「明细表记录ID」去重：唯一索引 + INSERT ... ON DUPLICATE KEY UPDATE。
 */
@Service
@RequiredArgsConstructor
public class RevenueContractImportService {

    /** 品牌关键词 → 营收主项目名（关键词按长度优先匹配，包含即可命中） */
    private static final Map<String, String> BRAND_TO_PROJECT = Map.ofEntries(
            Map.entry("皇家宠物", "皇家项目"),
            Map.entry("皇家", "皇家项目"),
            Map.entry("speedo", "Speedo"),
            Map.entry("速比涛", "Speedo"),
            Map.entry("飞鹤", "飞鹤"),
            Map.entry("澳优", "澳优"),
            Map.entry("佳贝艾特", "澳优"),
            Map.entry("海普诺凯", "澳优"),
            Map.entry("佳贝", "澳优"),
            Map.entry("海普", "澳优"),
            Map.entry("逢时", "逢时"),
            Map.entry("黄天鹅", "黄天鹅"));

    private static final String[] TYPE_KEYWORDS = {"会员通", "精准", "saas", "定制", "短信"};
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(\\d{4})[-/.年](\\d{1,2})");

    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueContractImportBatchMapper batchMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;

    @Transactional
    public RevenueImportResultVO importContracts(MultipartFile file, Long userId) {
        ParsedFile parsed = parse(file);
        if (parsed.entries().isEmpty()) {
            throw new IllegalArgumentException("未解析到合同明细，请确认上传的是 本年销售/交付总额明细 Excel");
        }
        RevenueContractImportBatch batch = new RevenueContractImportBatch();
        batch.setFileName(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        batch.setCreatedBy(userId);
        batch.setTotalCount(parsed.entries().size());
        int pendingCount = (int) parsed.entries().stream().filter(e -> e.getPending() == 1).count();
        batch.setSuccessCount(parsed.entries().size() - pendingCount);
        batch.setPendingCount(pendingCount);
        batchMapper.insert(batch);
        parsed.entries().forEach(e -> e.setBatchId(batch.getId()));
        contractEntryMapper.upsertBatch(parsed.entries());

        RevenueImportResultVO result = new RevenueImportResultVO();
        result.setBatchId(batch.getId());
        result.setTotalCount(batch.getTotalCount());
        result.setSuccessCount(batch.getSuccessCount());
        result.setPendingCount(batch.getPendingCount());
        return result;
    }

    public List<RevenueContractImportBatch> listBatches() {
        return batchMapper.selectList(new LambdaQueryWrapper<RevenueContractImportBatch>()
                .orderByDesc(RevenueContractImportBatch::getCreatedAt)
                .orderByDesc(RevenueContractImportBatch::getId));
    }

    public List<RevenueContractEntry> listPending() {
        return contractEntryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .eq(RevenueContractEntry::getPending, 1)
                .orderByAsc(RevenueContractEntry::getId));
    }

    /**
     * Returns every mapped contract. The optional year is retained for API compatibility;
     * mapping correction must not hide contracts whose sales month differs from the delivery year.
     */
    public List<RevenueContractMappingVO> listMapped(Integer year) {
        List<RevenueContractEntry> entries = contractEntryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .eq(RevenueContractEntry::getPending, 0)
                .orderByAsc(RevenueContractEntry::getId));
        Map<Long, BusinessLine> linesById = businessLineMapper.selectList(null).stream()
                .collect(Collectors.toMap(BusinessLine::getId, line -> line, (first, second) -> first));
        Map<Long, Project> projectsById = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, project -> project, (first, second) -> first));
        return entries.stream().map(entry -> toMappingVO(entry, linesById, projectsById)).toList();
    }

    private RevenueContractMappingVO toMappingVO(RevenueContractEntry entry) {
        BusinessLine line = entry.getBizLineId() == null ? null : businessLineMapper.selectById(entry.getBizLineId());
        Project project = entry.getProjectId() == null ? null : projectMapper.selectById(entry.getProjectId());
        return toMappingVO(entry,
                line == null ? Map.of() : Map.of(entry.getBizLineId(), line),
                project == null ? Map.of() : Map.of(entry.getProjectId(), project));
    }

    private RevenueContractMappingVO toMappingVO(RevenueContractEntry entry,
                                                  Map<Long, BusinessLine> linesById,
                                                  Map<Long, Project> projectsById) {
        RevenueContractMappingVO vo = new RevenueContractMappingVO();
        org.springframework.beans.BeanUtils.copyProperties(entry, vo);
        BusinessLine line = entry.getBizLineId() == null ? null : linesById.get(entry.getBizLineId());
        if (line != null) vo.setBusinessLineName(line.getName());
        Project project = entry.getProjectId() == null ? null : projectsById.get(entry.getProjectId());
        if (project != null) vo.setProjectName(project.getName());
        return vo;
    }

    @Transactional
    public RevenueContractMappingVO updateMapping(Long id, Long businessLineId, Long projectId) {
        RevenueContractEntry entry = contractEntryMapper.selectById(id);
        if (entry == null) throw new IllegalArgumentException("合同明细不存在");
        applyMapping(entry, businessLineId, projectId, true);
        entry.setUpdatedAt(LocalDateTime.now());
        contractEntryMapper.updateById(entry);
        return toMappingVO(entry);
    }

    private void applyMapping(RevenueContractEntry entry, Long businessLineId, Long projectId, boolean requireLine) {
        if (businessLineId == null) throw new IllegalArgumentException("业务线不能为空");
        BusinessLine line = businessLineMapper.selectById(businessLineId);
        if ((requireLine || projectId == null) && line == null) throw new IllegalArgumentException("业务线不存在");
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null) throw new IllegalArgumentException("项目不存在");
            if (!businessLineId.equals(project.getBusinessLineId())) throw new IllegalArgumentException("项目不属于该业务线");
        }
        entry.setBizLineId(businessLineId);
        entry.setProjectId(projectId);
        entry.setPending(0);
    }

    /** 人工指定待映射明细归属，复用统一映射校验。 */
    public void resolvePending(Long id, Long projectId, Long businessLineId, Long userId) {
        RevenueContractEntry entry = contractEntryMapper.selectById(id);
        if (entry == null) throw new IllegalArgumentException("合同明细不存在");
        if (!Integer.valueOf(1).equals(entry.getPending())) throw new IllegalArgumentException("该明细已映射项目，无需再次处理");
        if (projectId != null && businessLineId == null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null) throw new IllegalArgumentException("项目不存在");
            businessLineId = project.getBusinessLineId();
        }
        applyMapping(entry, businessLineId, projectId, false);
        entry.setUpdatedAt(LocalDateTime.now());
        contractEntryMapper.updateById(entry);
    }

    // ------------------------------------------------------------------ 解析

    private ParsedFile parse(MultipartFile file) {
        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1));
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full", (a, b) -> a));
        List<Project> projects = projectMapper.selectList(null);
        List<Long> enabledLineIds = lines.stream().map(BusinessLine::getId).toList();

        List<RevenueContractEntry> entries = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheetAt(0);
            Header header = locateHeader(sheet, formatter);
            Map<String, Integer> columns = header.columns();
            for (int i = header.row() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String detailNo = text(formatter, evaluator, row, columns, "明细表记录ID");
                String contractNo = text(formatter, evaluator, row, columns, "合同ID");
                String receivableText = text(formatter, evaluator, row, columns, "应收金额");
                if (!StringUtils.hasText(detailNo) && !StringUtils.hasText(contractNo)
                        && !StringUtils.hasText(receivableText)) {
                    continue;
                }
                if (!StringUtils.hasText(detailNo)) {
                    continue;
                }
                BigDecimal amount = parseDecimal(receivableText);
                if (amount == null) {
                    continue;
                }
                RevenueContractEntry entry = new RevenueContractEntry();
                entry.setDetailNo(detailNo.trim());
                entry.setContractNo(trimToNull(contractNo));
                entry.setContractName(trimToNull(text(formatter, evaluator, row, columns, "合同名称")));
                entry.setBrand(trimToNull(text(formatter, evaluator, row, columns, "品牌")));
                entry.setCustomer(trimToNull(text(formatter, evaluator, row, columns, "客户名称")));
                entry.setItemDesc(trimToNull(text(formatter, evaluator, row, columns, "款项内容")));
                String typeRaw = trimToNull(text(formatter, evaluator, row, columns, "收款款项类型"));
                entry.setBizLineRaw(typeRaw);
                entry.setReceivableAmount(amount);
                String saleMonth = monthText(text(formatter, evaluator, row, columns, "收款销售月份"));
                if (saleMonth == null) {
                    saleMonth = monthText(text(formatter, evaluator, row, columns, "应收日期"));
                }
                entry.setSaleMonth(saleMonth);
                entry.setDeliveryDate(dateText(text(formatter, evaluator, row, columns, "项目交付日期")));

                Assigned assigned = assign(entry.getBrand(), typeRaw, lines, lineMode, projects, enabledLineIds);
                entry.setBizLineId(assigned.lineId());
                entry.setProjectId(assigned.projectId());
                entry.setPending(assigned.pending() ? 1 : 0);
                entries.add(entry);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法解析合同 Excel: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("无法解析合同 Excel:")) {
                throw exception;
            }
            throw new IllegalArgumentException("无法解析合同 Excel: " + exception.getMessage(), exception);
        }
        return new ParsedFile(entries);
    }

    /** 归属：先按收款款项类型确定业务线，再仅在该线内按品牌匹配项目。 */
    private Assigned assign(String brand, String typeRaw, List<BusinessLine> lines, Map<Long, String> lineMode,
                            List<Project> projects, List<Long> enabledLineIds) {
        Long lineId = matchTypeLine(typeRaw, lines);
        if (lineId == null) {
            // 未知收款类型不得凭品牌跨线猜测。
            return new Assigned(null, null, true);
        }
        String targetName = brandTarget(brand);
        if (targetName != null) {
            Project brandProject = projects.stream()
                    .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(targetName)
                            && Objects.equals(p.getBusinessLineId(), lineId)
                            && enabledLineIds.contains(p.getBusinessLineId()))
                    .findFirst().orElse(null);
            if (brandProject != null) {
                return new Assigned(lineId, brandProject.getId(), false);
            }
        }
        // 仅 full 模式（定制/SAAS）需严格映射到系统项目，无品牌命中才待确认；
        // 其余业务线（会员通/精准等）按业务线优先直接落业务线级，不再进入待映射。
        boolean needProject = "full".equals(lineMode.get(lineId));
        return new Assigned(lineId, null, needProject);
    }

    private String brandTarget(String brand) {
        if (!StringUtils.hasText(brand)) {
            return null;
        }
        String lower = brand.toLowerCase(Locale.ROOT);
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> entry : BRAND_TO_PROJECT.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase(Locale.ROOT)) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    /**
     * 收款款项类型 → 业务线（会员通/精准/saas/定制 关键字，与既有营收导入一致）。
     * 短信充值类（如「全域-全渠道-全域京东文本短信」）固定归「全域精准」业务线。
     */
    private Long matchTypeLine(String typeRaw, List<BusinessLine> lines) {
        if (!StringUtils.hasText(typeRaw)) {
            return null;
        }
        String lower = typeRaw.toLowerCase(Locale.ROOT);
        if (lower.contains("短信")) {
            return lines.stream()
                    .filter(line -> line.getName() != null && line.getName().contains("精准"))
                    .map(BusinessLine::getId)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
        String keyword = null;
        for (String candidate : TYPE_KEYWORDS) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                keyword = candidate;
                break;
            }
        }
        if (keyword == null) {
            return null;
        }
        String finalKeyword = keyword;
        return lines.stream()
                .filter(line -> line.getName() != null
                        && line.getName().toLowerCase(Locale.ROOT).contains(finalKeyword.toLowerCase(Locale.ROOT)))
                .map(BusinessLine::getId)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /** 表头行 = 前 10 行内同时含「应收金额」「明细表记录ID」列名的行；按表头名定位列索引，不写死列号 */
    private Header locateHeader(Sheet sheet, DataFormatter formatter) {
        int headerIdx = -1;
        for (int i = 0; i <= Math.min(sheet.getLastRowNum(), 10); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            boolean hasAmount = false;
            boolean hasDetail = false;
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).trim();
                if ("应收金额".equals(value)) {
                    hasAmount = true;
                } else if ("明细表记录ID".equals(value)) {
                    hasDetail = true;
                }
            }
            if (hasAmount && hasDetail) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new IllegalArgumentException("无法解析合同 Excel: 未找到含「应收金额」「明细表记录ID」的表头行");
        }
        Map<String, Integer> columns = new HashMap<>();
        Row header = sheet.getRow(headerIdx);
        for (Cell cell : header) {
            String value = formatter.formatCellValue(cell).trim();
            if (!value.isEmpty() && !columns.containsKey(value)) {
                columns.put(value, cell.getColumnIndex());
            }
        }
        return new Header(headerIdx, columns);
    }

    private String text(DataFormatter formatter, FormulaEvaluator evaluator, Row row,
                        Map<String, Integer> columns, String header) {
        Integer index = columns.get(header);
        if (index == null || index >= row.getLastCellNum()) {
            return null;
        }
        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (RuntimeException e) {
            return formatter.formatCellValue(cell).trim();
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BigDecimal parseDecimal(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private LocalDate dateText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String monthText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = MONTH_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            return YearMonth.of(year, month).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Assigned(Long lineId, Long projectId, boolean pending) {
    }

    private record ParsedFile(List<RevenueContractEntry> entries) {
    }

    private record Header(int row, Map<String, Integer> columns) {
    }
}
