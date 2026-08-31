package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营收工时明细（按月从工时系统 Excel 导入）
 */
@Data
@TableName("revenue_worklog_entry")
public class RevenueWorklogEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    @TableField("`year_month`")
    private String yearMonth;
    private String businessLineName;
    private Long businessLineId;
    private String projectNameRaw;
    private Long projectId;
    /** project=项目（交付+产研）/sales=销售 */
    private String workType;
    /** specific=具体销售项目/pool=商机集合/other=其他 */
    private String salesKind;
    private Long salesProjectId;
    private String employeeNo;
    private String employeeName;
    private String department;
    /** 人月 */
    private BigDecimal hours;
    private String workNote;
    private String specialNote;
    /** 商机集合自动标签，逗号分隔 */
    private String tags;
    private Integer pending;
    private Long createdBy;
    private LocalDateTime createdAt;
}
