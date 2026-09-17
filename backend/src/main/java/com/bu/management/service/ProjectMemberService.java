package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.ProjectMemberRequest;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectMember;
import com.bu.management.entity.User;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectMemberMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.ProjectMemberVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目成员服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    /**
     * 添加项目成员
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectMember addMember(ProjectMemberRequest request) {
        // 验证项目是否存在
        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        // 验证用户是否存在
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        // 检查用户是否已在项目中
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getProjectId, request.getProjectId())
               .eq(ProjectMember::getUserId, request.getUserId());
        if (projectMemberMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("用户已在项目中");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(request.getProjectId());
        member.setUserId(request.getUserId());
        member.setRole(request.getRole());

        projectMemberMapper.insert(member);
        return member;
    }

    /**
     * 移除项目成员
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long projectId, Long userId) {
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getProjectId, projectId)
               .eq(ProjectMember::getUserId, userId);

        ProjectMember member = projectMemberMapper.selectOne(wrapper);
        if (member == null) {
            throw new RuntimeException("项目成员不存在");
        }

        projectMemberMapper.deleteById(member.getId());
    }

    /**
     * 查询项目成员列表
     */
    public List<ProjectMemberVO> listByProject(Long projectId) {
        // 验证项目是否存在
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getProjectId, projectId)
               .orderByAsc(ProjectMember::getJoinedAt);

        List<ProjectMember> members = projectMemberMapper.selectList(wrapper);

        return members.stream()
                .map(member -> {
                    ProjectMemberVO vo = new ProjectMemberVO();
                    vo.setId(member.getId());
                    vo.setProjectId(member.getProjectId());
                    vo.setProjectName(project.getName());
                    vo.setUserId(member.getUserId());
                    vo.setRole(member.getRole());
                    vo.setJoinedAt(member.getJoinedAt());

                    // 查询用户信息
                    User user = userMapper.selectById(member.getUserId());
                    if (user != null) {
                        vo.setUsername(user.getUsername());
                        vo.setRealName(user.getRealName());
                    }

                    return vo;
                })
                .collect(Collectors.toList());
    }

    /**
     * 查询用户参与的项目列表
     */
    public List<ProjectMemberVO> listByUser(Long userId) {
        // 验证用户是否存在
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getUserId, userId)
               .orderByDesc(ProjectMember::getJoinedAt);

        List<ProjectMember> members = projectMemberMapper.selectList(wrapper);

        return members.stream()
                .map(member -> {
                    ProjectMemberVO vo = new ProjectMemberVO();
                    vo.setId(member.getId());
                    vo.setProjectId(member.getProjectId());
                    vo.setUserId(member.getUserId());
                    vo.setUsername(user.getUsername());
                    vo.setRealName(user.getRealName());
                    vo.setRole(member.getRole());
                    vo.setJoinedAt(member.getJoinedAt());

                    // 查询项目信息
                    Project project = projectMapper.selectById(member.getProjectId());
                    if (project != null) {
                        vo.setProjectName(project.getName());
                    }

                    return vo;
                })
                .collect(Collectors.toList());
    }
}
