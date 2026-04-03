package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事项表
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Data
@TableName("issue")
public class Issue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联类型：需求/任务
     */
    private String relatedType;

    /**
     * 关联ID
     */
    private Long relatedId;

    /**
     * 事项标题
     */
    private String title;

    /**
     * 事项描述
     */
    private String description;

    /**
     * 事项类型：问题/风险/变更
     */
    private String issueType;

    /**
     * 严重程度：低/中/高/紧急
     */
    private String severity;

    /**
     * 状态：待处理/处理中/已解决/已关闭
     */
    private String status;

    /**
     * 负责人ID
     */
    private Long assigneeId;

    /**
     * 创建人ID
     */
    private Long createdBy;

    /**
     * 解决方案
     */
    private String resolution;

    /**
     * 解决时间
     */
    private LocalDateTime resolvedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
