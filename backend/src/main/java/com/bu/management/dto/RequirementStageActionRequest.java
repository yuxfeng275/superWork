package com.bu.management.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 需求阶段动作请求 DTO
 */
@Data
public class RequirementStageActionRequest {

    /**
     * 动作类型：
     * start_eval / reject / start_test / test_pass / test_fail / go_online
     */
    @NotBlank(message = "动作类型不能为空")
    private String action;
}
