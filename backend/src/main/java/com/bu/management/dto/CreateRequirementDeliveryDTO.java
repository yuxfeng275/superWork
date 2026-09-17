package com.bu.management.dto;

import lombok.Data;

/**
 * 创建需求交付 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateRequirementDeliveryDTO {

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 交付人ID
     */
    private Long deliveredBy;

    /**
     * 交付说明
     */
    private String deliveryNotes;
}
