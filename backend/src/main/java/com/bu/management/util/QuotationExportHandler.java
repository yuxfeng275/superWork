package com.bu.management.util;

import com.bu.management.entity.Quotation;
import com.bu.management.entity.QuotationBrandScope;
import com.bu.management.entity.QuotationLineItem;
import com.bu.management.service.QuotationService;
import com.bu.management.vo.QuotationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class QuotationExportHandler {
    private final QuotationService quotationService;

    public ResponseEntity<byte[]> export(Long quotationId) throws IOException {
        QuotationVO vo = quotationService.getQuotationDetail(quotationId);
        if (vo == null) return ResponseEntity.notFound().build();

        Quotation quotation = new Quotation();
        quotation.setQuotationNo(vo.getQuotationNo());
        quotation.setQuoteDate(vo.getQuoteDate());
        quotation.setValidityDays(vo.getValidityDays());
        quotation.setTaxMode(vo.getTaxMode());
        quotation.setCustomerName(vo.getCustomerName());
        quotation.setContactPerson(vo.getContactPerson());
        quotation.setContactPhone(vo.getContactPhone());
        quotation.setContactEmail(vo.getContactEmail());
        quotation.setDeliveryPeriod(vo.getDeliveryPeriod());
        quotation.setFirstYearTotalExTax(vo.getFirstYearTotalExTax());
        quotation.setFirstYearTotalInclTax(vo.getFirstYearTotalInclTax());
        quotation.setSubsequentYearTotalExTax(vo.getSubsequentYearTotalExTax());
        quotation.setSubsequentYearTotalInclTax(vo.getSubsequentYearTotalInclTax());
        quotation.setQuotationNote(vo.getQuotationNote());

        QuotationExcelExporter exporter = new QuotationExcelExporter(
                quotation,
                vo.getLineItems(),
                vo.getBrandScopes()
        );
        byte[] bytes = exporter.export();

        String filename = URLEncoder.encode("报价单_" + vo.getQuotationNo() + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }
}