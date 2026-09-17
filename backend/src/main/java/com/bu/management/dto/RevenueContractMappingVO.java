package com.bu.management.dto;

import com.bu.management.entity.RevenueContractEntry;
import lombok.Data;

@Data
public class RevenueContractMappingVO extends RevenueContractEntry {
    private String businessLineName;
    private String projectName;
}
