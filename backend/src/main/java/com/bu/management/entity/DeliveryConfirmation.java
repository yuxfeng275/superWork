package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待交付合同确认（delivery_confirmation）：一行对应一条合同明细（revenue_contract_entry）的
 * 当前确认状态。revoked_at IS NULL = 确认生效中；取消确认置 revoked_*；再次确认复用同一行。
 * 已确认的合同计入项目利润预估块的营收与利润；未确认仅展示，不计入利润。
 */
@Data
@TableName("delivery_confirmation")
public class DeliveryConfirmation {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** revenue_contract_entry.id（合同明细行） */
    private Long contractEntryId;
    /** 确认时预计交付月份 YYYY-MM；NULL=服务结束时间缺失（未定月份） */
    private String expectedMonth;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private Long revokedBy;
    /** NULL=确认生效中 */
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
