package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.ProjectRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.vo.ProjectTreeNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 项目服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final BusinessLineMapper businessLineMapper;

    /**
     * 创建项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project create(ProjectRequest request) {
        // 验证业务线是否存在
        BusinessLine businessLine = businessLineMapper.selectById(request.getBusinessLineId());
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        // 验证父项目（如果有）
        Project parent = null;
        if (request.getParentId() != null) {
            parent = projectMapper.selectById(request.getParentId());
            if (parent == null) {
                throw new RuntimeException("父项目不存在");
            }
            if (parent.getLevel() >= 2) {
                throw new RuntimeException("不支持三级及以上项目");
            }
        }

        // 检查项目编码是否已存在
        if (StringUtils.hasText(request.getCode())) {
            LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Project::getCode, request.getCode());
            if (projectMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("项目编码已存在");
            }
        }

        Project project = new Project();
        project.setBusinessLineId(request.getBusinessLineId());
        project.setParentId(request.getParentId());
        project.setLevel(request.getParentId() == null ? 1 : 2);
        project.setName(request.getName());
        project.setCode(request.getCode());
        project.setManagerId(request.getManagerId());
        project.setStatus(request.getStatus() != null ? request.getStatus() : 1);

        // 构建完整路径
        if (parent != null) {
            project.setFullPath(parent.getFullPath() + "/" + request.getName());
        } else {
            project.setFullPath(request.getName());
        }

        projectMapper.insert(project);
        return project;
    }

    /**
     * 更新项目
     */
    @Transactional(rollbackFor = Exception.class)
    public Project update(Long id, ProjectRequest request) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        // 验证业务线是否存在
        BusinessLine businessLine = businessLineMapper.selectById(request.getBusinessLineId());
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        // 检查项目编码是否已被其他项目使用
        if (StringUtils.hasText(request.getCode()) && !request.getCode().equals(project.getCode())) {
            LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Project::getCode, request.getCode());
            if (projectMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("项目编码已存在");
            }
        }

        project.setBusinessLineId(request.getBusinessLineId());
        project.setName(request.getName());
        project.setCode(request.getCode());
        project.setManagerId(request.getManagerId());
        if (request.getStatus() != null) {
            project.setStatus(request.getStatus());
        }

        // 如果名称改变，需要更新完整路径
        if (!request.getName().equals(project.getName())) {
            updateFullPath(project, request.getName());
        }

        projectMapper.updateById(project);
        return project;
    }

    /**
     * 更新完整路径（包括子项目）
     */
    private void updateFullPath(Project project, String newName) {
        String oldPath = project.getFullPath();
        String newPath;

        if (project.getParentId() != null) {
            Project parent = projectMapper.selectById(project.getParentId());
            newPath = parent.getFullPath() + "/" + newName;
        } else {
            newPath = newName;
        }

        project.setFullPath(newPath);

        // 更新所有子项目的路径
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getParentId, project.getId());
        List<Project> children = projectMapper.selectList(wrapper);

        for (Project child : children) {
            String childPath = child.getFullPath().replace(oldPath, newPath);
            child.setFullPath(childPath);
            projectMapper.updateById(child);
        }
    }

    /**
     * 删除项目
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        // 检查是否有子项目
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getParentId, id);
        if (projectMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("存在子项目，无法删除");
        }

        projectMapper.deleteById(id);
    }

    /**
     * 获取项目详情
     */
    public Project getById(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }
        return project;
    }

    /**
     * 分页查询项目列表
     */
    public Page<Project> list(Integer page, Integer size, Long businessLineId, String name, Integer status) {
        Page<Project> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();

        // 业务线筛选
        if (businessLineId != null) {
            wrapper.eq(Project::getBusinessLineId, businessLineId);
        }

        // 名称模糊查询
        if (StringUtils.hasText(name)) {
            wrapper.like(Project::getName, name);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(Project::getStatus, status);
        }

        // 按创建时间倒序
        wrapper.orderByDesc(Project::getCreatedAt);

        return projectMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 获取项目树
     */
    public List<ProjectTreeNode> getTree(Long businessLineId) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (businessLineId != null) {
            wrapper.eq(Project::getBusinessLineId, businessLineId);
        }
        wrapper.orderByAsc(Project::getLevel, Project::getCreatedAt);

        List<Project> allProjects = projectMapper.selectList(wrapper);

        // 转换为 TreeNode
        List<ProjectTreeNode> allNodes = allProjects.stream()
                .map(this::convertToTreeNode)
                .collect(Collectors.toList());

        // 构建树形结构
        Map<Long, ProjectTreeNode> nodeMap = allNodes.stream()
                .collect(Collectors.toMap(ProjectTreeNode::getId, node -> node));

        List<ProjectTreeNode> rootNodes = new ArrayList<>();
        for (ProjectTreeNode node : allNodes) {
            if (node.getParentId() == null) {
                rootNodes.add(node);
            } else {
                ProjectTreeNode parent = nodeMap.get(node.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(node);
                }
            }
        }

        return rootNodes;
    }

    /**
     * 转换为树节点
     */
    private ProjectTreeNode convertToTreeNode(Project project) {
        ProjectTreeNode node = new ProjectTreeNode();
        BeanUtils.copyProperties(project, node);
        return node;
    }
}
