package com.bu.management.dto;

import lombok.Data;

@Data
public class KpiNoteRequest {
    private String deviationReason;
    /** 1=是 0=否 */
    private Integer isAbnormal;
    private String countermeasure;
}
