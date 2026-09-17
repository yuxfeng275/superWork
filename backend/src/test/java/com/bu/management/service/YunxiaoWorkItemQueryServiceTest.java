package com.bu.management.service;

import com.bu.management.entity.Project;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.bu.management.vo.WorkItemOverviewItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YunxiaoWorkItemQueryServiceTest {

    @Mock private YunxiaoWorkitemCacheMapper cacheMapper;
    @Mock private YunxiaoProjectMappingMapper projectMappingMapper;
    @Mock private YunxiaoUserMappingMapper userMappingMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private UserMapper userMapper;
    @Mock private DataPermissionService dataPermissionService;

    @InjectMocks private YunxiaoWorkItemQueryService service;

    @Test
    void sharedCloudSpaceProducesOneBugWithAllMappedProjects() {
        when(cacheMapper.selectList(any())).thenReturn(List.of(
                cache("bug-1", "Bug", "space-1", "修复中", "IN_PROGRESS"),
                cache("task-1", "Task", "space-1", "进行中", "IN_PROGRESS")
        ));
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(
                mapping(10L, "space-1"), mapping(11L, "space-1")
        ));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(
                project(10L, "经营中台"), project(11L, "数据分析")
        ));
        when(userMappingMapper.selectList(any())).thenReturn(List.of());
        when(dataPermissionService.isBuAdmin("DIRECTOR")).thenReturn(true);

        List<WorkItemOverviewItem> result = service.listCloudItems(
                "Bug", 1L, "DIRECTOR", null, null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRecordKey()).isEqualTo("yunxiao:bug-1");
        assertThat(result.get(0).getProjectIds()).containsExactly(10L, 11L);
        assertThat(result.get(0).getProjectNames()).containsExactly("经营中台", "数据分析");
        assertThat(result.get(0).isReadOnly()).isTrue();
    }

    @Test
    void projectScopedRoleOnlySeesMappedProjectsItCanAccess() {
        when(cacheMapper.selectList(any())).thenReturn(List.of(
                cache("bug-1", "Bug", "space-1", "待修复", "PENDING"),
                cache("bug-2", "Bug", "space-2", "待修复", "PENDING")
        ));
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(
                mapping(10L, "space-1"), mapping(11L, "space-1"), mapping(20L, "space-2")
        ));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(
                project(10L, "经营中台"), project(11L, "数据分析"), project(20L, "内部平台")
        ));
        when(userMappingMapper.selectList(any())).thenReturn(List.of());
        when(dataPermissionService.isBuAdmin("TECH_ARCHITECT")).thenReturn(false);
        when(dataPermissionService.isProjectRole("TECH_ARCHITECT")).thenReturn(true);
        when(dataPermissionService.getUserProjectIds(9L)).thenReturn(List.of(11L));

        List<WorkItemOverviewItem> result = service.listCloudItems(
                "Bug", 9L, "TECH_ARCHITECT", null, null, "PENDING", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getYunxiaoWorkitemId()).isEqualTo("bug-1");
        assertThat(result.get(0).getProjectIds()).containsExactly(11L);
    }

    private YunxiaoWorkitemCache cache(String id, String category, String space,
                                        String status, String normalizedStatus) {
        YunxiaoWorkitemCache item = new YunxiaoWorkitemCache();
        item.setYunxiaoWorkitemId(id);
        item.setSerialNumber(id.toUpperCase());
        item.setCategory(category);
        item.setYunxiaoProjectId(space);
        item.setTitle(id + " title");
        item.setStatus(status);
        item.setNormalizedStatus(normalizedStatus);
        item.setActive(1);
        item.setSourceUpdatedAt(LocalDateTime.of(2026, 8, 10, 10, 0));
        return item;
    }

    private YunxiaoProjectMapping mapping(Long projectId, String space) {
        YunxiaoProjectMapping mapping = new YunxiaoProjectMapping();
        mapping.setProjectId(projectId);
        mapping.setYunxiaoProjectId(space);
        mapping.setSyncEnabled(1);
        return mapping;
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setFullPath(name);
        return project;
    }
}
