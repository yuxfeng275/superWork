package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 需求设计汇总表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("requirement_design")
public class RequirementDesign {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求ID
     */
    private Long requirementId;

    /**
     * 原型设计状态：未开始/进行中/已完成
     */
    private String prototypeStatus;

    /**
     * UI设计状态：未开始/进行中/已完成
     */
    private String uiStatus;

    /**
     * 技术方案状态：未开始/进行中/已完成
     */
    private String techSolutionStatus;

    /**
     * 全部完成时间
     */
    private LocalDateTime allCompletedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
