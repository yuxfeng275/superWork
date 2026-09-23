package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按月待交付的销售确认：一行 = 月份 × 业务线 × 销售 的人为确认结论。
 * 确认由管理人员与销售线下逐一沟通后录入，备注记录沟通结论。
 */
@Data
@TableName("revenue_delivery_confirmation")
public class RevenueDeliveryConfirmation {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMABLE = "CONFIRMABLE";
    public static final String STATUS_UNCONFIRMABLE = "UNCONFIRMABLE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long bizLineId;
    /** YYYY-MM（取 revenue_contract_entry.sale_month）；year_month 是 MySQL 保留字，必须反引号 */
    @com.baomidou.mybatisplus.annotation.TableField("`year_month`")
    private String yearMonth;
    /** 销售姓名；源数据缺销售时用 _UNSET_ 占位 */
    private String salesOwner;
    private String status;
    /** 确认备注（与销售的沟通结论） */
    private String remark;
    private Long confirmedBy;
    private String confirmedByName;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
