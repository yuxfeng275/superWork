package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目实体
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@TableName("project")
public class Project {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属业务线
     */
    private Long businessLineId;

    /**
     * 父项目ID，NULL表示主项目
     */
    private Long parentId;

    /**
     * 层级：1=主项目，2=子项目
     */
    private Integer level;

    /**
     * 项目名称
     */
    private String name;

    /**
     * 完整路径，如"皇家/PMS"
     */
    private String fullPath;

    /**
     * 项目编码
     */
    private String code;

    /**
     * 项目经理ID
     */
    private Long managerId;

    /**
     * 状态：1=进行中，0=已结束
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
