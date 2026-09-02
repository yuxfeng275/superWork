package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 营收合同明细（项目交付导入）。
 * detail_no = Excel 明细表记录ID，为唯一去重键；
 * 金额单位统一为元；已交付判定实时用 delivery_date &lt;= 今天，不落状态。
 */
@Data
@TableName("revenue_contract_entry")
public class RevenueContractEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    /** 合同ID */
    private String contractNo;
    /** 明细表记录ID（唯一） */
    private String detailNo;
    private String contractName;
    private String brand;
    private String customer;
    private String itemDesc;
    /** 收款款项类型（原始业务线名） */
    private String bizLineRaw;
    private Long bizLineId;
    private Long projectId;
    /** 应收金额（元） */
    private BigDecimal receivableAmount;
    /** 收款销售月份 YYYY-MM（按年视图隔离用） */
    private String saleMonth;
    /** 项目交付日期；NULL=未交付 */
    private LocalDate deliveryDate;
    /** 1=待人工映射项目 */
    private Integer pending;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
