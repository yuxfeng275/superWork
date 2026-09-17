package com.bu.management.dto;

import lombok.Data;

/**
 * 验收需求 DTO
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
public class AcceptRequirementDTO {

    /**
     * 验收人ID
     */
    private Long acceptedBy;

    /**
     * 验收备注
     */
    private String acceptanceNotes;
}
