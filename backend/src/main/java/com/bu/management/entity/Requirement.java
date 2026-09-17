package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 需求实体
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@TableName("requirement")
public class Requirement {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 需求编号，自动生成
     */
    private String reqNo;

    /**
     * 需求标题
     */
    private String title;

    /**
     * 需求描述
     */
    private String description;

    /**
     * 类型：项目需求/产品需求
     */
    private String type;

    /**
     * 所属业务线
     */
    private Long businessLineId;

    /**
     * 所属项目，产品需求可为空
     */
    private Long projectId;

    /**
     * 客户联系人ID，项目需求必填
     */
    private Long customerContactId;

    /**
     * 状态
     */
    private String status;

    /**
     * 优先级：高/中/低
     */
    private String priority;

    /**
     * 来源：客户/商务/内部
     */
    private String source;

    /**
     * 客户期望上线时间
     */
    private LocalDate expectedOnlineDate;

    /**
     * 评估后的预估上线时间
     */
    private LocalDate estimatedOnlineDate;

    /**
     * 实际上线时间
     */
    private LocalDate actualOnlineDate;

    /**
     * 创建人
     */
    private Long creatorId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
