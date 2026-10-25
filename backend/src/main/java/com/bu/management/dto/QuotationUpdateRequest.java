package com.bu.management.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class QuotationUpdateRequest {
    private String customerName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private String deliveryPeriod;
    private LocalDate quoteDate;
    private Integer validityDays;
    private String quotationNote;
    private List<QuotationGenerateRequest.LineItemOverride> lineItemOverrides;
}
