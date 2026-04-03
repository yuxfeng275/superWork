package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求交付表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("requirement_delivery")
public class RequirementDelivery {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 交付时间
     */
    private LocalDateTime deliveredAt;

    /**
     * 交付人ID
     */
    private Long deliveredBy;

    /**
     * 交付说明
     */
    private String deliveryNotes;

    /**
     * 验收人ID
     */
    private Long acceptedBy;

    /**
     * 验收时间
     */
    private LocalDateTime acceptedAt;

    /**
     * 验收备注
     */
    private String acceptanceNotes;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
