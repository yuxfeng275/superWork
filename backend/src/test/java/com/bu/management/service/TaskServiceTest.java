package com.bu.management.service;

import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.TaskOverviewResponse;
import com.bu.management.vo.WorkItemOverviewItem;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService 测试")
class TaskServiceTest {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private RequirementMapper requirementMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private YunxiaoWorkItemQueryService yunxiaoWorkItemQueryService;

    @InjectMocks
    private TaskService taskService;

    @Test
    @DisplayName("创建任务时补齐默认 taskType 和 createdBy")
    void createTask_setsDefaultTaskTypeAndCreatedBy() {
        Requirement requirement = new Requirement();
        requirement.setId(1L);

        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setRequirementId(1L);
        dto.setTitle("联调接口");
        dto.setAssigneeId(9L);

        when(requirementMapper.selectById(1L)).thenReturn(requirement);
        when(taskMapper.insert(any(Task.class))).thenReturn(1);

        Task result = taskService.createTask(dto, 42L);

        assertThat(result).isNotNull();
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getTaskType()).isEqualTo("开发任务");
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(42L);
        assertThat(captor.getValue().getStatus()).isEqualTo("待开始");
    }

    @Test
    @DisplayName("任务概览合并云效任务并保持云效记录只读")
    void overview_mergesReadOnlyYunxiaoTasks() {
        Task local = new Task();
        local.setId(1L);
        local.setRequirementId(2L);
        local.setTitle("本地任务");
        local.setStatus("进行中");
        local.setEstimatedHours(new BigDecimal("5"));
        local.setActualHours(new BigDecimal("2"));
        local.setEndDate(java.time.LocalDate.now().minusDays(1));

        Requirement requirement = new Requirement();
        requirement.setId(2L);
        requirement.setProjectId(3L);
        requirement.setTitle("本地需求");

        Project project = new Project();
        project.setId(3L);
        project.setName("经营中台");

        WorkItemOverviewItem cloud = new WorkItemOverviewItem();
        cloud.setRecordKey("yunxiao:task-1");
        cloud.setDataSource("YUNXIAO");
        cloud.setReadOnly(true);
        cloud.setYunxiaoWorkitemId("task-1");
        cloud.setTitle("云效任务");
        cloud.setStatus("开发中");
        cloud.setNormalizedStatus("IN_PROGRESS");
        cloud.setProjectId(3L);
        cloud.setProjectName("经营中台");
        cloud.setProjectIds(List.of(3L));
        cloud.setProjectNames(List.of("经营中台"));
        cloud.setAssigneeName("张凯");
        cloud.setEstimatedHours(new BigDecimal("8"));
        cloud.setActualHours(new BigDecimal("3"));
        cloud.setDueDate(java.time.LocalDate.now().plusDays(3));

        when(taskMapper.selectList(any())).thenReturn(List.of(local));
        when(requirementMapper.selectBatchIds(any())).thenReturn(List.of(requirement));
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project));
        when(yunxiaoWorkItemQueryService.listCloudItems(
                "Task", 7L, "DIRECTOR", null, null, "IN_PROGRESS", null
        )).thenReturn(List.of(cloud));

        TaskOverviewResponse response = taskService.getTaskOverview(
                7L, "DIRECTOR", null, null, "IN_PROGRESS", null);

        assertThat(response.getTasks()).hasSize(2);
        assertThat(response.getTasks()).anyMatch(item -> "local:1".equals(item.getRecordKey()) && !item.isReadOnly());
        assertThat(response.getTasks()).anyMatch(item -> "yunxiao:task-1".equals(item.getRecordKey()) && item.isReadOnly());
        assertThat(response.getAnalysis().getStatusDistribution()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getKey()).isEqualTo("IN_PROGRESS");
                    assertThat(row.getCount()).isEqualTo(2);
                });
        assertThat(response.getAnalysis().getProjectDistribution()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getLabel()).isEqualTo("经营中台");
                    assertThat(row.getCount()).isEqualTo(2);
                });
        assertThat(response.getAnalysis().getOwnerDistribution())
                .anySatisfy(row -> {
                    assertThat(row.getLabel()).isEqualTo("张凯");
                    assertThat(row.getCount()).isEqualTo(1);
                });
        assertThat(response.getAnalysis().getTotalEstimatedHours()).isEqualByComparingTo("13");
        assertThat(response.getAnalysis().getTotalActualHours()).isEqualByComparingTo("5");
        assertThat(response.getAnalysis().getOverdueIncompleteCount()).isEqualTo(1);
        assertThat(response.getAnalysis().getOverdueOwnerDistribution()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getLabel()).isEqualTo("未分配");
                    assertThat(row.getCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("需求不存在时创建任务失败")
    void createTask_requirementMissing_throwsException() {
        CreateTaskDTO dto = new CreateTaskDTO();
        dto.setRequirementId(99L);
        dto.setTitle("联调接口");

        when(requirementMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> taskService.createTask(dto, 42L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("需求不存在");
    }
}
