package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.ProjectMember;
import com.bu.management.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数据权限服务
 *
 * @author BU Team
 * @since 2026-04-04
 */
@Service
@RequiredArgsConstructor
public class DataPermissionService {

    private final ProjectMemberMapper projectMemberMapper;

    /**
     * 检查用户是否为BU管理员
     */
    public boolean isBuAdmin(String role) {
        return "BU_ADMIN".equals(role);
    }

    /**
     * 检查用户是否为项目经理或技术经理
     */
    public boolean isProjectRole(String role) {
        return "PM".equals(role) || "TECH_MANAGER".equals(role);
    }

    /**
     * 获取用户参与的所有项目ID列表
     */
    public List<Long> getUserProjectIds(Long userId) {
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getUserId, userId);
        List<ProjectMember> members = projectMemberMapper.selectList(wrapper);
        return members.stream()
                .map(ProjectMember::getProjectId)
                .distinct()
                .toList();
    }

    /**
     * 检查用户是否为指定项目的成员
     */
    public boolean isProjectMember(Long userId, Long projectId) {
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getUserId, userId)
               .eq(ProjectMember::getProjectId, projectId);
        return projectMemberMapper.selectCount(wrapper) > 0;
    }
}
