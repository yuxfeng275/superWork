package com.bu.management.service;

import com.bu.management.vo.KpiReportVO;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPI 周报导出：复刻 2026KPI.xlsx 结构（业务线行 × 月度 YTD 营收/毛利 + 达成率 + 环比增量），
 * 附「偏差备注明细」sheet。金额单位：万元。
 */
@Service
@RequiredArgsConstructor
public class KpiExportService {

    private final KpiReportService reportService;

    public byte[] exportReport(int year) {
        KpiReportVO report = reportService.buildReport(year);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle header = headerStyle(workbook);
            CellStyle red = coloredStyle(workbook, IndexedColors.ROSE);
            CellStyle yellow = coloredStyle(workbook, IndexedColors.LIGHT_YELLOW);

            Sheet sheet = workbook.createSheet("KPI周报");
            List<Integer> months = monthsOf(report);

            // 表头两行：业务线 | 指标 | KPI目标(营收/毛利) | X月YTD(营收/毛利) ...
            Row head1 = sheet.createRow(0);
            Row head2 = sheet.createRow(1);
            head1.createCell(0).setCellValue("业务线");
            head2.createCell(0).setCellValue("");
            head1.createCell(1).setCellValue("指标");
            head1.createCell(2).setCellValue("KPI目标");
            head2.createCell(2).setCellValue("营收");
            head2.createCell(3).setCellValue("毛利");
            int col = 4;
            for (Integer month : months) {
                head1.createCell(col).setCellValue(month + "月YTD");
                head2.createCell(col).setCellValue("营收");
                head2.createCell(col + 1).setCellValue("毛利");
                sheet.addMergedRegion(new CellRangeAddress(0, 0, col, col + 1));
                col += 2;
            }
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 1, 1));
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 2, 3));
            for (int i = 0; i < col; i++) {
                if (head1.getCell(i) == null) head1.createCell(i);
                head1.getCell(i).setCellStyle(header);
                if (head2.getCell(i) == null) head2.createCell(i);
                head2.getCell(i).setCellStyle(header);
                sheet.setColumnWidth(i, 12 * 256);
            }

            int rowIdx = 2;
            List<String[]> noteRows = new ArrayList<>();
            for (KpiReportVO.GroupRow group : report.getGroups()) {
                int startRow = rowIdx;
                rowIdx = writeGroupRows(sheet, rowIdx, group, months, red, yellow, noteRows);
                sheet.addMergedRegion(new CellRangeAddress(startRow, rowIdx - 1, 0, 0));
                Row firstRow = sheet.getRow(startRow);
                firstRow.createCell(0).setCellValue(group.getReportGroup());
            }
            // 合计行
            KpiReportVO.GroupTotal total = report.getTotal();
            Row totalRow = sheet.createRow(rowIdx);
            totalRow.createCell(0).setCellValue("合计");
            totalRow.createCell(1).setCellValue("实际");
            totalRow.createCell(2).setCellValue(wan(total.getYtdRevenue()));
            totalRow.createCell(3).setCellValue(wan(total.getYtdProfit()));
            totalRow.createCell(4).setCellValue("达成率 营收 " + rateText(total.getRevenueRate())
                    + " / 毛利 " + rateText(total.getProfitRate())
                    + "｜最新周环比 营收 " + deltaText(total.getWeekDeltaRevenue())
                    + " / 毛利 " + deltaText(total.getWeekDeltaProfit()));

            // 偏差备注明细 sheet
            Sheet noteSheet = workbook.createSheet("偏差备注明细");
            Row noteHead = noteSheet.createRow(0);
            String[] noteHeaders = {"周截止日", "业务线", "指标", "级别", "偏差原因", "是否异常", "对策", "状态"};
            for (int i = 0; i < noteHeaders.length; i++) {
                noteHead.createCell(i).setCellValue(noteHeaders[i]);
                noteHead.getCell(i).setCellStyle(header);
                noteSheet.setColumnWidth(i, 16 * 256);
            }
            int noteRowIdx = 1;
            for (String[] noteRow : noteRows) {
                Row row = noteSheet.createRow(noteRowIdx++);
                for (int i = 0; i < noteRow.length; i++) {
                    row.createCell(i).setCellValue(noteRow[i] == null ? "" : noteRow[i]);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出失败: " + e.getMessage(), e);
        }
    }

    private int writeGroupRows(Sheet sheet, int rowIdx, KpiReportVO.GroupRow group, List<Integer> months,
                               CellStyle red, CellStyle yellow, List<String[]> noteRows) {
        Row actualRow = sheet.createRow(rowIdx++);
        Row rateRow = sheet.createRow(rowIdx++);
        Row deltaRow = sheet.createRow(rowIdx++);
        actualRow.createCell(1).setCellValue("实际");
        rateRow.createCell(1).setCellValue("达成率");
        deltaRow.createCell(1).setCellValue("环比增量");
        actualRow.createCell(2).setCellValue(wan(group.getRevenueTarget()));
        actualRow.createCell(3).setCellValue(wan(group.getProfitTarget()));
        rateRow.createCell(2).setCellValue("100%");
        rateRow.createCell(3).setCellValue("100%");

        Map<Integer, KpiReportVO.SnapshotRow> monthEnd = monthEndSnapshots(group);
        int col = 4;
        for (Integer month : months) {
            KpiReportVO.SnapshotRow snapshot = monthEnd.get(month);
            KpiReportVO.SnapshotRow prev = month > 1 ? monthEnd.get(month - 1) : null;
            writeCell(actualRow, col, snapshot == null ? null : wan(snapshot.getYtdRevenue()));
            writeCell(actualRow, col + 1, snapshot == null ? null : wan(snapshot.getYtdProfit()));
            writeCell(rateRow, col, snapshot == null ? null : rateText(snapshot.getRevenueRate()));
            writeCell(rateRow, col + 1, snapshot == null ? null : rateText(snapshot.getProfitRate()));
            writeDeltaCell(sheet, deltaRow, col, snapshot, prev, "revenue", group.getReportGroup(), red, yellow, noteRows);
            writeDeltaCell(sheet, deltaRow, col + 1, snapshot, prev, "profit", group.getReportGroup(), red, yellow, noteRows);
            col += 2;
        }
        return rowIdx;
    }

    private void writeDeltaCell(Sheet sheet, Row deltaRow, int col, KpiReportVO.SnapshotRow snapshot,
                                KpiReportVO.SnapshotRow prev, String metric, String group,
                                CellStyle red, CellStyle yellow, List<String[]> noteRows) {
        if (snapshot == null) {
            return;
        }
        BigDecimal current = "revenue".equals(metric) ? snapshot.getYtdRevenue() : snapshot.getYtdProfit();
        BigDecimal previous = prev == null ? null
                : ("revenue".equals(metric) ? prev.getYtdRevenue() : prev.getYtdProfit());
        BigDecimal delta = previous == null ? current : current.subtract(previous);
        deltaRow.createCell(col).setCellValue(deltaText(delta));

        KpiReportVO.NoteRow note = snapshot.getNotes() == null ? null : snapshot.getNotes().stream()
                .filter(n -> metric.equals(n.getMetric())).findFirst().orElse(null);
        if (note != null) {
            deltaRow.getCell(col).setCellStyle("red".equals(note.getAlertLevel()) ? red : yellow);
            noteRows.add(new String[]{
                    snapshot.getWeekEndDate().toString(), group,
                    "revenue".equals(metric) ? "营收" : "毛利",
                    "red".equals(note.getAlertLevel()) ? "红(<=0)" : "黄(偏小)",
                    note.getDeviationReason(),
                    note.getIsAbnormal() == null ? "" : (note.getIsAbnormal() == 1 ? "是" : "否"),
                    note.getCountermeasure(),
                    "done".equals(note.getStatus()) ? "已填写" : "待填写"});
        } else if (delta.compareTo(BigDecimal.ZERO) <= 0) {
            deltaRow.getCell(col).setCellStyle(red);
        }
    }

    private Map<Integer, KpiReportVO.SnapshotRow> monthEndSnapshots(KpiReportVO.GroupRow group) {
        Map<Integer, KpiReportVO.SnapshotRow> monthEnd = new LinkedHashMap<>();
        for (KpiReportVO.SnapshotRow snapshot : group.getSnapshots()) {
            LocalDate date = snapshot.getWeekEndDate();
            monthEnd.merge(date.getMonthValue(), snapshot,
                    (a, b) -> a.getWeekEndDate().isAfter(b.getWeekEndDate()) ? a : b);
        }
        return monthEnd;
    }

    private List<Integer> monthsOf(KpiReportVO report) {
        List<Integer> months = new ArrayList<>();
        for (KpiReportVO.GroupRow group : report.getGroups()) {
            for (KpiReportVO.SnapshotRow snapshot : group.getSnapshots()) {
                int month = snapshot.getWeekEndDate().getMonthValue();
                if (!months.contains(month)) {
                    months.add(month);
                }
            }
        }
        months.sort(Integer::compareTo);
        return months;
    }

    // ------------------------------------------------------------------ 文字版摘要

    public String buildSummary(int year) {
        KpiReportVO report = reportService.buildReport(year);
        StringBuilder sb = new StringBuilder();
        sb.append("KPI周报（").append(year).append("年）\n");
        LocalDate latestWeek = null;
        for (KpiReportVO.GroupRow group : report.getGroups()) {
            KpiReportVO.SnapshotRow latest = group.getLatest();
            if (latest == null) continue;
            latestWeek = latest.getWeekEndDate();
            sb.append("【").append(group.getReportGroup()).append("】")
                    .append(" YTD营收 ").append(wan(latest.getYtdRevenue())).append("万")
                    .append("（达成率 ").append(rateText(latest.getRevenueRate())).append("）")
                    .append("，周环比 ").append(deltaText(latest.getWeekDeltaRevenue())).append("万")
                    .append("；毛利 ").append(wan(latest.getYtdProfit())).append("万")
                    .append("，周环比 ").append(deltaText(latest.getWeekDeltaProfit())).append("万\n");
        }
        sb.append("【合计】YTD营收 ").append(wan(report.getTotal().getYtdRevenue())).append("万")
                .append("（达成率 ").append(rateText(report.getTotal().getRevenueRate())).append("）")
                .append("；毛利 ").append(wan(report.getTotal().getYtdProfit())).append("万\n");
        if (latestWeek != null) {
            sb.append("数据截止：").append(latestWeek).append("\n");
        }
        // 异常与待办
        List<String> abnormal = new ArrayList<>();
        int pendingCount = 0;
        for (KpiReportVO.GroupRow group : report.getGroups()) {
            KpiReportVO.SnapshotRow latest = group.getLatest();
            if (latest == null || latest.getNotes() == null) continue;
            for (KpiReportVO.NoteRow note : latest.getNotes()) {
                String label = group.getReportGroup() + "·" + ("revenue".equals(note.getMetric()) ? "营收" : "毛利");
                if ("pending".equals(note.getStatus())) {
                    pendingCount++;
                    abnormal.add("⚠️ " + label + " 环比异常（待填写偏差备注）");
                } else {
                    abnormal.add("• " + label + "：" + nullToEmpty(note.getDeviationReason())
                            + (note.getIsAbnormal() != null && note.getIsAbnormal() == 1
                            ? "｜对策：" + nullToEmpty(note.getCountermeasure()) : "（正常波动）"));
                }
            }
        }
        if (!abnormal.isEmpty()) {
            sb.append("\n偏差说明：\n");
            abnormal.forEach(line -> sb.append(line).append("\n"));
        }
        if (pendingCount > 0) {
            sb.append("\n待填写偏差备注 ").append(pendingCount).append(" 条");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 工具

    private void writeCell(Row row, int col, Object value) {
        if (value instanceof Double d) {
            row.createCell(col).setCellValue(d);
        } else if (value instanceof String s) {
            row.createCell(col).setCellValue(s);
        }
    }

    private Double wan(BigDecimal value) {
        if (value == null) return null;
        return value.divide(new BigDecimal("10000"), 1, RoundingMode.HALF_UP).doubleValue();
    }

    private String rateText(BigDecimal rate) {
        if (rate == null) return "—";
        return rate.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP) + "%";
    }

    private String deltaText(BigDecimal delta) {
        if (delta == null) return "—";
        BigDecimal wan = delta.divide(new BigDecimal("10000"), 1, RoundingMode.HALF_UP);
        return (wan.signum() > 0 ? "+" : "") + wan;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle coloredStyle(Workbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
