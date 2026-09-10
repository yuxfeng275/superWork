package com.bu.management.util;

import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationBrandScope;
import com.bu.management.entity.QuotationLineItem;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QuotationExcelExporter {

    private static final String TITLE_FONT = "微软雅黑";
    private final Quotation quotation;
    private final List<QuotationLineItem> lineItems;
    private final List<QuotationBrandScope> brandScopes;

    public QuotationExcelExporter(Quotation quotation, List<QuotationLineItem> lineItems,
                                   List<QuotationBrandScope> brandScopes) {
        this.quotation = quotation;
        this.lineItems = lineItems;
        this.brandScopes = brandScopes;
    }

    public byte[] export() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            createOverviewSheet(workbook);
            createDetailSheet(workbook);
            createFunctionSheet(workbook);
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                workbook.write(bos);
                return bos.toByteArray();
            }
        }
    }

    // ==================== Sheet 1: 报价总览 ====================
    private void createOverviewSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("报价总览");
        int rowIdx = 0;

        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle labelStyle = createLabelStyle(workbook);
        CellStyle valueStyle = createValueStyle(workbook);
        CellStyle amountStyle = createAmountStyle(workbook);

        // Title
        Row titleRow = sheet.createRow(rowIdx++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("报价单");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 3));

        rowIdx++; // blank

        // 报价单信息
        rowIdx = addInfoRow(sheet, rowIdx, "报价单号：", quotation.getQuotationNo(), labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "报价日期：", quotation.getQuoteDate() != null ? quotation.getQuoteDate().toString() : "", labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "有效期：", quotation.getValidityDays() + "天", labelStyle, valueStyle);

        rowIdx++; // blank

        // 委托方信息
        rowIdx = addSectionHeader(sheet, rowIdx, "委托方信息", headerStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "委托方/品牌公司：", nvl(quotation.getCustomerName()), labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "项目负责人：", nvl(quotation.getContactPerson()), labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "电话：", nvl(quotation.getContactPhone()), labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "邮件：", nvl(quotation.getContactEmail()), labelStyle, valueStyle);
        rowIdx = addInfoRow(sheet, rowIdx, "交货期：", nvl(quotation.getDeliveryPeriod()), labelStyle, valueStyle);

        rowIdx++; // blank

        // 报价合计
        rowIdx = addSectionHeader(sheet, rowIdx, "报价合计", headerStyle);
        boolean isTaxIncluded = "TAX_INCLUDED".equals(quotation.getTaxMode());

        if (isTaxIncluded) {
            rowIdx = addInfoRow(sheet, rowIdx, "首年含税总价：", formatMoney(quotation.getFirstYearTotalInclTax()), labelStyle, amountStyle);
            rowIdx = addInfoRow(sheet, rowIdx, "次年及以后含税总价：", formatMoney(quotation.getSubsequentYearTotalInclTax()), labelStyle, amountStyle);
        } else {
            rowIdx = addInfoRow(sheet, rowIdx, "首年未税总价：", formatMoney(quotation.getFirstYearTotalExTax()), labelStyle, amountStyle);
            rowIdx = addInfoRow(sheet, rowIdx, "次年及以后未税总价：", formatMoney(quotation.getSubsequentYearTotalExTax()), labelStyle, amountStyle);
        }

        rowIdx++; // blank

        // 报价范围
        if (brandScopes != null && !brandScopes.isEmpty()) {
            rowIdx = addSectionHeader(sheet, rowIdx, "报价范围", headerStyle);
            Row scopeHeader = sheet.createRow(rowIdx++);
            String[] scopeCols = {"品牌", "店铺", "说明", "目标"};
            for (int i = 0; i < scopeCols.length; i++) {
                Cell c = scopeHeader.createCell(i);
                c.setCellValue(scopeCols[i]);
                c.setCellStyle(headerStyle);
            }
            for (QuotationBrandScope scope : brandScopes) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue(nvl(scope.getBrand()));
                r.createCell(1).setCellValue(nvl(scope.getStore()));
                r.createCell(2).setCellValue(nvl(scope.getDescription()));
                r.createCell(3).setCellValue(nvl(scope.getTarget()));
                for (int i = 0; i < 4; i++) r.getCell(i).setCellStyle(valueStyle);
            }
        }

        rowIdx++; // blank

        // 报价说明
        if (quotation.getQuotationNote() != null) {
            rowIdx = addSectionHeader(sheet, rowIdx, "报价说明", headerStyle);
            Row noteRow = sheet.createRow(rowIdx);
            noteRow.createCell(0).setCellValue(quotation.getQuotationNote());
            noteRow.getCell(0).setCellStyle(valueStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 3));
        }

        // Column widths
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 5000);
        sheet.setColumnWidth(2, 5000);
        sheet.setColumnWidth(3, 5000);
    }

    // ==================== Sheet 2: 报价单 ====================
    private void createDetailSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("报价单");
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle valueStyle = createValueStyle(workbook);
        CellStyle amountStyle = createAmountStyle(workbook);

        int rowIdx = 0;
        Row titleRow = sheet.createRow(rowIdx++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("报价明细");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 8));

        rowIdx++; // blank

        boolean isTaxIncluded = "TAX_INCLUDED".equals(quotation.getTaxMode());

        // Group line items by section
        Map<String, List<QuotationLineItem>> grouped = new LinkedHashMap<>();
        for (QuotationLineItem li : lineItems) {
            grouped.computeIfAbsent(li.getSection(), k -> new java.util.ArrayList<>()).add(li);
        }

        for (Map.Entry<String, List<QuotationLineItem>> entry : grouped.entrySet()) {
            String section = entry.getKey();
            List<QuotationLineItem> items = entry.getValue();

            // Section header
            Row sectionRow = sheet.createRow(rowIdx++);
            Cell secCell = sectionRow.createCell(0);
            secCell.setCellValue(section);
            secCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 8));

            // Column headers
            Row colRow = sheet.createRow(rowIdx++);
            String[] headers;
            if (isTaxIncluded) {
                headers = new String[]{"序号", "选择", "项目名称", "功能描述", "数量", "含税单价", "折扣率", "含税小计", "备注"};
            } else {
                headers = new String[]{"序号", "选择", "项目名称", "功能描述", "数量", "未税单价", "税率", "折扣率", "未税小计", "含税小计", "备注"};
            }
            for (int i = 0; i < headers.length; i++) {
                Cell c = colRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(headerStyle);
            }

            // Items
            int seq = 0;
            for (QuotationLineItem li : items) {
                if (li.getIsSelected() == null || li.getIsSelected() == 0) continue;
                seq++;
                Row r = sheet.createRow(rowIdx++);
                int col = 0;
                cell(r, col++, String.valueOf(seq), valueStyle);
                cell(r, col++, "✓", valueStyle);
                cell(r, col++, nvl(li.getItemName()), valueStyle);
                cell(r, col++, nvl(li.getDescription()), valueStyle);
                cell(r, col++, formatNum(li.getQuantity()), valueStyle);

                if (isTaxIncluded) {
                    BigDecimal unitInclTax = null;
                    if (li.getUnitPriceExTax() != null && li.getTaxRate() != null) {
                        unitInclTax = li.getUnitPriceExTax().multiply(BigDecimal.ONE.add(li.getTaxRate()));
                    }
                    cell(r, col++, formatMoney(unitInclTax), amountStyle);
                    cell(r, col++, formatPct(li.getDiscountRate()), valueStyle);
                    cell(r, col++, formatMoney(li.getSubtotalInclTax()), amountStyle);
                } else {
                    cell(r, col++, formatMoney(li.getUnitPriceExTax()), amountStyle);
                    cell(r, col++, formatPct(li.getTaxRate()), valueStyle);
                    cell(r, col++, formatPct(li.getDiscountRate()), valueStyle);
                    cell(r, col++, formatMoney(li.getSubtotalExTax()), amountStyle);
                    cell(r, col++, formatMoney(li.getSubtotalInclTax()), amountStyle);
                }
                cell(r, col++, nvl(li.getRemark()), valueStyle);
            }
        }

        // Column widths
        sheet.setColumnWidth(0, 1500);
        sheet.setColumnWidth(1, 1500);
        sheet.setColumnWidth(2, 6000);
        sheet.setColumnWidth(3, 8000);
        sheet.setColumnWidth(4, 2000);
        for (int i = 5; i <= 10; i++) sheet.setColumnWidth(i, 4000);
    }

    // ==================== Sheet 3: 产品功能及服务介绍 ====================
    private void createFunctionSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("产品功能及服务介绍");
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle valueStyle = createValueStyle(workbook);

        int rowIdx = 0;
        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.createCell(0).setCellValue("产品功能及服务介绍");
        titleRow.getCell(0).setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        rowIdx++;

        Row colRow = sheet.createRow(rowIdx++);
        String[] headers = {"分组", "项目名称", "功能描述", "收费方式"};
        for (int i = 0; i < headers.length; i++) {
            Cell c = colRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(headerStyle);
        }

        for (QuotationLineItem li : lineItems) {
            Row r = sheet.createRow(rowIdx++);
            cell(r, 0, nvl(li.getSection()), valueStyle);
            cell(r, 1, nvl(li.getItemName()), valueStyle);
            cell(r, 2, nvl(li.getDescription()), valueStyle);
            cell(r, 3, nvl(li.getChargeMethod()), valueStyle);
        }

        sheet.setColumnWidth(0, 4000);
        sheet.setColumnWidth(1, 6000);
        sheet.setColumnWidth(2, 12000);
        sheet.setColumnWidth(3, 4000);
    }

    // ==================== helpers ====================

    private static int addSectionHeader(Sheet sheet, int rowIdx, String text, CellStyle style) {
        Row row = sheet.createRow(rowIdx++);
        Cell cell = row.createCell(0);
        cell.setCellValue(text);
        cell.setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 3));
        return rowIdx;
    }

    private static int addInfoRow(Sheet sheet, int rowIdx, String label, String value, CellStyle labelStyle, CellStyle valueStyle) {
        Row row = sheet.createRow(rowIdx++);
        Cell lc = row.createCell(0);
        lc.setCellValue(label);
        lc.setCellStyle(labelStyle);
        Cell vc = row.createCell(1);
        vc.setCellValue(value);
        vc.setCellStyle(valueStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 1, 3));
        return rowIdx;
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        c.setCellStyle(style);
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static String formatMoney(BigDecimal v) {
        if (v == null) return "";
        return "¥" + String.format("%,.2f", v);
    }

    private static String formatNum(BigDecimal v) {
        if (v == null) return "1";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String formatPct(BigDecimal v) {
        if (v == null) return "";
        return v.multiply(new BigDecimal("100")).setScale(0).toString() + "%";
    }

    // ==================== styles ====================

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName(TITLE_FONT);
        f.setBold(true);
        f.setFontHeightInPoints((short) 18);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName(TITLE_FONT);
        f.setBold(true);
        f.setFontHeightInPoints((short) 11);
        s.setFont(f);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.LEFT);
        return s;
    }

    private static CellStyle createLabelStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName(TITLE_FONT);
        f.setBold(true);
        f.setFontHeightInPoints((short) 11);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.RIGHT);
        return s;
    }

    private static CellStyle createValueStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName(TITLE_FONT);
        f.setFontHeightInPoints((short) 11);
        s.setFont(f);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setWrapText(true);
        return s;
    }

    private static CellStyle createAmountStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setFontName(TITLE_FONT);
        f.setBold(true);
        f.setFontHeightInPoints((short) 11);
        s.setFont(f);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        s.setAlignment(HorizontalAlignment.RIGHT);
        return s;
    }
}