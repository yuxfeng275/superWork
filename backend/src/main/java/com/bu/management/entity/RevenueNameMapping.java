package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营收导入名称映射记忆：人工指定过的原始名称 → 系统归属，后续导入自动套用。
 */
@Data
@TableName("revenue_name_mapping")
public class RevenueNameMapping {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String rawBusinessLine;
    private String rawProjectName;
    private Long businessLineId;
    private Long projectId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
