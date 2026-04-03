package com.bu.management.dto;

import lombok.Data;

/**
 * 创建需求确认 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class CreateRequirementConfirmationDTO {

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 确认类型：客户确认/内部确认
     */
    private String confirmationType;

    /**
     * 确认人ID
     */
    private Long confirmedBy;

    /**
     * 确认备注
     */
    private String confirmationNotes;
}
