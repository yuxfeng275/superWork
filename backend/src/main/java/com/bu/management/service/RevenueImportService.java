package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueImportBatch;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueImportBatchMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.dto.RevenueImportResultVO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 营收工时/成本明细导入。
 * 工时模板列：工号|姓名|部门|业务线|项目|工时占比|工作说明|特殊说明
 * 成本模板列：月份|业务线名称|项目名|员工数|项目数|工时|工时成本|人月成本
 * 同一月份同一类型重复导入 = 整月替换。已完结月份拒绝导入。
 */
@Service
@RequiredArgsConstructor
public class RevenueImportService {

    private final RevenueWorklogEntryMapper worklogEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueImportBatchMapper importBatchMapper;
    private final RevenueMappingResolver mappingResolver;
    private final RevenueMonthService monthService;

    @Transactional
    public RevenueImportResultVO importWorklog(MultipartFile file, String yearMonth, Long userId) {
        monthService.assertNotClosed(yearMonth);
        RevenueImportBatch batch = newBatch("worklog", yearMonth, file.getOriginalFilename(), userId);

        List<RevenueWorklogEntry> parsed = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String businessLine = cellText(formatter, row, 3);
                String projectName = cellText(formatter, row, 4);
                String hoursText = cellText(formatter, row, 5);
                if (!StringUtils.hasText(businessLine) && !StringUtils.hasText(projectName)) {
                    continue;
                }
                BigDecimal hours = parseDecimal(hoursText);
                if (hours == null) {
                    continue;
                }
                RevenueWorklogEntry entry = new RevenueWorklogEntry();
                entry.setBatchId(batch.getId());
                entry.setYearMonth(yearMonth);
                entry.setBusinessLineName(businessLine);
                entry.setProjectNameRaw(projectName);
                entry.setEmployeeNo(cellText(formatter, row, 0));
                entry.setEmployeeName(cellText(formatter, row, 1));
                entry.setDepartment(cellText(formatter, row, 2));
                entry.setHours(hours);
                entry.setWorkNote(cellText(formatter, row, 6));
                entry.setSpecialNote(cellText(formatter, row, 7));

                RevenueMappingResolver.Resolved resolved = mappingResolver.resolve(businessLine, projectName);
                entry.setBusinessLineId(resolved.businessLineId());
                entry.setProjectId(resolved.projectId());
                entry.setWorkType(resolved.workType());
                entry.setSalesKind(resolved.salesKind());
                entry.setSalesProjectId(resolved.salesProjectId());
                entry.setPending(resolved.pending() ? 1 : 0);
                if ("pool".equals(resolved.salesKind())) {
                    entry.setTags(mappingResolver.tagWorkNote(entry.getWorkNote()));
                }
                parsed.add(entry);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析工时 Excel: " + exception.getMessage(), exception);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("未解析到工时数据，请确认上传的是工时数据_业务线明细 Excel");
        }

        worklogEntryMapper.delete(new LambdaQueryWrapper<RevenueWorklogEntry>()
                .eq(RevenueWorklogEntry::getYearMonth, yearMonth));
        parsed.forEach(worklogEntryMapper::insert);
        return finishBatch(batch, parsed.size(),
                (int) parsed.stream().filter(item -> item.getPending() == 0).count());
    }

    @Transactional
    public RevenueImportResultVO importCost(MultipartFile file, Long userId) {
        List<RevenueCostEntry> parsed = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String month = cellText(formatter, row, 0);
                String businessLine = cellText(formatter, row, 1);
                String projectName = cellText(formatter, row, 2);
                if (!StringUtils.hasText(month) || !StringUtils.hasText(projectName)) {
                    continue;
                }
                BigDecimal hours = parseDecimal(cellText(formatter, row, 5));
                BigDecimal cost = parseDecimal(cellText(formatter, row, 6));
                if (hours == null || cost == null) {
                    continue;
                }
                RevenueCostEntry entry = new RevenueCostEntry();
                entry.setYearMonth(month.trim());
                entry.setBusinessLineName(businessLine);
                entry.setProjectNameRaw(projectName);
                entry.setEmployeeCount(parseInt(cellText(formatter, row, 3)));
                entry.setHours(hours);
                entry.setCostAmount(cost);
                entry.setPersonMonthCost(parseDecimal(cellText(formatter, row, 7)));

                RevenueMappingResolver.Resolved resolved = mappingResolver.resolve(businessLine, projectName);
                entry.setBusinessLineId(resolved.businessLineId());
                entry.setProjectId(resolved.projectId());
                entry.setWorkType(resolved.workType());
                entry.setSalesKind(resolved.salesKind());
                entry.setSalesProjectId(resolved.salesProjectId());
                entry.setPending(resolved.pending() ? 1 : 0);
                parsed.add(entry);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法解析成本 Excel: " + exception.getMessage(), exception);
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("未解析到成本数据，请确认上传的是成本分析_项目 Excel");
        }

        String yearMonth = parsed.get(0).getYearMonth();
        monthService.assertNotClosed(yearMonth);
        RevenueImportBatch batch = newBatch("cost", yearMonth, file.getOriginalFilename(), userId);
        parsed.forEach(item -> item.setBatchId(batch.getId()));

        costEntryMapper.delete(new LambdaQueryWrapper<RevenueCostEntry>()
                .eq(RevenueCostEntry::getYearMonth, yearMonth));
        parsed.forEach(costEntryMapper::insert);
        return finishBatch(batch, parsed.size(),
                (int) parsed.stream().filter(item -> item.getPending() == 0).count());
    }

    public List<RevenueImportBatch> listBatches(String importType) {
        return importBatchMapper.selectList(new LambdaQueryWrapper<RevenueImportBatch>()
                .eq(StringUtils.hasText(importType), RevenueImportBatch::getImportType, importType)
                .orderByDesc(RevenueImportBatch::getCreatedAt));
    }

    private RevenueImportBatch newBatch(String type, String yearMonth, String fileName, Long userId) {
        RevenueImportBatch batch = new RevenueImportBatch();
        batch.setImportType(type);
        batch.setYearMonth(yearMonth);
        batch.setFileName(fileName == null ? "" : fileName);
        batch.setCreatedBy(userId);
        batch.setCreatedAt(LocalDateTime.now());
        batch.setTotalCount(0);
        batch.setSuccessCount(0);
        batch.setPendingCount(0);
        importBatchMapper.insert(batch);
        return batch;
    }

    private RevenueImportResultVO finishBatch(RevenueImportBatch batch, int total, int success) {
        batch.setTotalCount(total);
        batch.setSuccessCount(success);
        batch.setPendingCount(total - success);
        importBatchMapper.updateById(batch);
        RevenueImportResultVO result = new RevenueImportResultVO();
        result.setBatchId(batch.getId());
        result.setTotalCount(total);
        result.setSuccessCount(success);
        result.setPendingCount(total - success);
        return result;
    }

    private String cellText(DataFormatter formatter, Row row, int index) {
        return formatter.formatCellValue(row.getCell(index)).trim();
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

    private Integer parseInt(String text) {
        BigDecimal value = parseDecimal(text);
        return value == null ? null : value.intValue();
    }
}
