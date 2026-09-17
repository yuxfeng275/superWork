package com.bu.management.dto;

import lombok.Data;
import java.util.List;

@Data
public class QuotationUpdateRequest {
    private String customerName;
    private String contactPerson;
    private String contactPhone;
    private String contactEmail;
    private String deliveryPeriod;
    private List<QuotationGenerateRequest.LineItemOverride> lineItemOverrides;
}