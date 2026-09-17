package com.bu.management.service;

import com.bu.management.dto.ProjectRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.CustomerContactMapper;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.ProjectMemberMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private BusinessLineMapper businessLineMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private RequirementMapper requirementMapper;

    @Mock
    private CustomerContactMapper customerContactMapper;

    @Mock
    private IssueMapper issueMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @InjectMocks
    private ProjectService projectService;

    @Test
    void createAllowsAnyActiveTeamMember() {
        ProjectRequest request = buildRequest();
        User developer = new User();
        developer.setId(9L);
        developer.setRole("FULL_STACK_ENGINEER");
        developer.setStatus(1);

        when(businessLineMapper.selectById(1L)).thenReturn(new BusinessLine());
        when(userMapper.selectById(9L)).thenReturn(developer);

        when(projectMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        Project created = projectService.create(request);

        assertEquals(9L, created.getManagerId());
    }

    @Test
    void createAllowsSolutionManager() {
        ProjectRequest request = buildRequest();
        User manager = new User();
        manager.setId(9L);
        manager.setRole("SOLUTION_MANAGER");
        manager.setStatus(1);

        when(businessLineMapper.selectById(1L)).thenReturn(new BusinessLine());
        when(userMapper.selectById(9L)).thenReturn(manager);
        when(projectMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        Project created = projectService.create(request);

        assertEquals(100L, created.getId());
        assertEquals(9L, created.getManagerId());
        verify(projectMapper).insert(any(Project.class));
    }

    @Test
    void updateRenamedChildRefreshesFullPath() {
        Project child = new Project();
        child.setId(12L);
        child.setBusinessLineId(1L);
        child.setParentId(1L);
        child.setLevel(2);
        child.setName("PMS");
        child.setFullPath("皇家项目/PMS");
        child.setCode("ROYAL-PMS");
        child.setManagerId(9L);
        child.setStatus(1);

        Project parent = new Project();
        parent.setId(1L);
        parent.setFullPath("皇家项目");

        User manager = new User();
        manager.setId(9L);
        manager.setRole("SOLUTION_MANAGER");
        manager.setStatus(1);

        ProjectRequest request = buildRequest();
        request.setParentId(1L);
        request.setName("PMS升级");
        request.setCode("ROYAL-PMS");

        when(projectMapper.selectById(12L)).thenReturn(child);
        when(projectMapper.selectById(1L)).thenReturn(parent);
        when(businessLineMapper.selectById(1L)).thenReturn(new BusinessLine());
        when(userMapper.selectById(9L)).thenReturn(manager);

        Project updated = projectService.update(12L, request);

        assertEquals("PMS升级", updated.getName());
        assertEquals("皇家项目/PMS升级", updated.getFullPath());
        verify(projectMapper).updateById(child);
    }

    @Test
    void deleteDetachesHistoricalRecordsBeforeDeletingProject() {
        Project project = new Project();
        project.setId(5L);
        project.setName("SAAS平台");

        when(projectMapper.selectById(5L)).thenReturn(project);
        when(projectMapper.selectCount(any())).thenReturn(0L);

        projectService.delete(5L);

        verify(requirementMapper, times(2)).update(isNull(), any());
        verify(issueMapper).update(isNull(), any());
        verify(customerContactMapper).delete(any());
        verify(projectMapper).deleteById(5L);
    }

    private ProjectRequest buildRequest() {
        ProjectRequest request = new ProjectRequest();
        request.setBusinessLineId(1L);
        request.setName("客户中台");
        request.setCode("CRM");
        request.setManagerId(9L);
        request.setStatus(1);
        return request;
    }
}
