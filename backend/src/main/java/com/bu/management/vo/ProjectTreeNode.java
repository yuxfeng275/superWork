package com.bu.management.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目树节点 VO
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
public class ProjectTreeNode {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 所属业务线
     */
    private Long businessLineId;

    /**
     * 父项目ID
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
     * 完整路径
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

    /**
     * 子项目列表
     */
    private List<ProjectTreeNode> children;
}
