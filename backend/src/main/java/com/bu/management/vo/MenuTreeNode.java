package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 侧边栏动态菜单树节点。分区组节点 path 为伪路径（/sec-*、/base、/system），不跳转。
 */
@Data
@AllArgsConstructor
public class MenuTreeNode {
    private Long id;
    private String name;
    private String icon;
    private String path;
    private Integer sortOrder;
    private List<MenuTreeNode> children;
}
