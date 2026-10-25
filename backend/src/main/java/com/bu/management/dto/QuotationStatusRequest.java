package com.bu.management.dto;

import lombok.Data;

@Data
public class QuotationStatusRequest {
    private String status;
    private String operator;
    private String comment;
}