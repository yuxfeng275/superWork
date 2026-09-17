package com.bu.management.service;

import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoEstimatedEffortMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.bu.management.vo.BuDashboardResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuDashboardServiceTest {

    @Mock
    private BuDirectionService directionService;
    @Mock
    private WorklogComplianceService worklogComplianceService;
    @Mock
    private YunxiaoIntegrationService integrationService;
    @Mock
    private RequirementMapper requirementMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private YunxiaoUserMappingMapper userMappingMapper;
    @Mock
    private YunxiaoWorkitemCacheMapper workitemCacheMapper;
    @Mock
    private YunxiaoEstimatedEffortMapper estimatedEffortMapper;
    @Mock
    private YunxiaoEffortRecordMapper effortRecordMapper;

    @InjectMocks
    private BuDashboardService service;

    @Test
    void cloudWorkWithoutEstimatesIsVisibleAndDoesNotReportAvailableCapacity() {
        User user = new User();
        user.setId(9L);
        user.setUsername("congning");
        user.setRealName("丛宁");
        user.setRole("SOLUTION_MANAGER");
        user.setStatus(1);
        when(userMapper.selectList(isNull())).thenReturn(List.of(user));
        when(userMapper.selectList(any())).thenReturn(List.of(user));

        YunxiaoUserMapping mapping = new YunxiaoUserMapping();
        mapping.setUserId(9L);
        mapping.setYunxiaoUserId("cloud-user-9");
        mapping.setSyncEnabled(1);
        when(userMappingMapper.selectList(any())).thenReturn(List.of(mapping));

        YunxiaoWorkitemCache workitem = new YunxiaoWorkitemCache();
        workitem.setYunxiaoWorkitemId("workitem-1");
        workitem.setYunxiaoAssigneeId("cloud-user-9");
        workitem.setTitle("8月需求");
        workitem.setStatus("设计中");
        when(workitemCacheMapper.selectList(isNull())).thenReturn(List.of(workitem));

        when(directionService.list()).thenReturn(List.of());
        when(projectMapper.selectList(isNull())).thenReturn(List.of());
        when(requirementMapper.selectList(isNull())).thenReturn(List.of());
        when(taskMapper.selectList(isNull())).thenReturn(List.of());
        when(estimatedEffortMapper.selectList(isNull())).thenReturn(List.of());
        when(effortRecordMapper.selectList(isNull())).thenReturn(List.of());
        when(worklogComplianceService.audit(any(), any())).thenReturn(List.of());
        when(worklogComplianceService.expectedHours(any(), any())).thenReturn(BigDecimal.valueOf(16));
        when(integrationService.getStatus()).thenReturn(new BuDashboardResponse.IntegrationStatus());

        BuDashboardResponse response = service.getDashboard(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 4),
                10
        );

        assertThat(response.getCapacity()).hasSize(1);
        BuDashboardResponse.CapacityItem capacity = response.getCapacity().get(0);
        assertThat(capacity.getActiveTaskCount()).isEqualTo(1);
        assertThat(capacity.getActiveWork()).containsExactly("8月需求");
        assertThat(capacity.getPlannedHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(capacity.getLoadStatus()).isEqualTo("未知");
        assertThat(capacity.getDataCompleteness()).contains("未登记预计工时");
    }
}
