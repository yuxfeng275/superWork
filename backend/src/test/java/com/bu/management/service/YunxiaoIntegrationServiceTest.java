package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.dto.YunxiaoProjectMappingRequest;
import com.bu.management.entity.Project;
import com.bu.management.entity.YunxiaoEffortRecord;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.integration.YunxiaoClient;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoEstimatedEffortMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YunxiaoIntegrationServiceTest {

    @Mock
    private YunxiaoConfigService configService;
    @Mock
    private YunxiaoClient client;
    @Mock
    private YunxiaoProjectMappingMapper projectMappingMapper;
    @Mock
    private YunxiaoUserMappingMapper userMappingMapper;
    @Mock
    private YunxiaoWorkitemCacheMapper workitemCacheMapper;
    @Mock
    private YunxiaoEffortRecordMapper effortRecordMapper;
    @Mock
    private YunxiaoEstimatedEffortMapper estimatedEffortMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private YunxiaoIntegrationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void statusNormalizationDistinguishesTestingFromTestedAndRejected() {
        assertThat(YunxiaoWorkItemConstants.normalizeStatus(null, "测试中"))
                .isEqualTo("IN_PROGRESS");
        assertThat(YunxiaoWorkItemConstants.normalizeStatus(null, "已测试"))
                .isEqualTo("COMPLETED");
        assertThat(YunxiaoWorkItemConstants.normalizeStatus(null, "已拒绝"))
                .isEqualTo("COMPLETED");
    }

    @Test
    void syncAllParsesEpochSecondEffortDatesInShanghaiTimezone() throws Exception {
        when(configService.getRuntimeConfig()).thenReturn(new YunxiaoRuntimeConfig(
                true,
                "center",
                "https://openapi-rdc.aliyuncs.com",
                "organization-id",
                "token",
                "PAGE",
                null,
                null,
                null
        ));

        YunxiaoProjectMapping mapping = new YunxiaoProjectMapping();
        mapping.setId(1L);
        mapping.setProjectId(1L);
        mapping.setYunxiaoProjectId("cloud-project-1");
        mapping.setCategory("Req");
        mapping.setSyncEnabled(1);
        mapping.setFullSyncedAt(LocalDateTime.now().minusDays(10));
        mapping.setLastSyncedAt(LocalDateTime.now().minusDays(1));
        mapping.setLastSyncStatus("SUCCESS");
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(mapping));

        when(client.searchWorkitems(
                eq("cloud-project-1"),
                eq("Req,Task,Bug"),
                any(LocalDate.class)
        )).thenReturn(List.of(
                objectMapper.readTree("""
                        {
                          "id": "workitem-1",
                          "serialNumber": "TASK-101",
                          "categoryId": "Task",
                          "subject": "测试工作项",
                          "status": {"displayName": "修复中", "statusType": "IN_PROGRESS"},
                          "assignedTo": {"id": "cloud-user-1", "name": "测试用户"},
                          "gmtCreate": 1785541230000,
                          "gmtModified": 1786312353000,
                          "customFieldValues": [{
                            "fieldId": "ExpCompletionTime",
                            "fieldName": "期望完成时间",
                            "values": [{"identifier": "2026-08-08 00:00:00"}]
                          }, {
                            "fieldId": "80",
                            "fieldName": "计划完成时间",
                            "values": [{"identifier": "2026-08-10 23:59:59"}]
                          }]
                        }
                        """)
        ));
        when(client.listEstimatedEfforts("workitem-1")).thenReturn(List.of());
        when(client.listEffortRecords("workitem-1")).thenReturn(List.of(
                objectMapper.readTree("""
                        {
                          "id": "effort-1",
                          "owner": {"id": "cloud-user-1", "name": "测试用户"},
                          "gmtStart": "1778256000",
                          "actualTime": 8
                        }
                        """)
        ));

        assertThat(service.syncAll()).containsExactly("1:SUCCESS:1");

        ArgumentCaptor<YunxiaoEffortRecord> recordCaptor =
                ArgumentCaptor.forClass(YunxiaoEffortRecord.class);
        verify(effortRecordMapper).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getWorkDate()).isEqualTo(LocalDate.of(2026, 5, 9));
        verify(projectMappingMapper).update(
                isNull(),
                any(UpdateWrapper.class)
        );
        ArgumentCaptor<YunxiaoWorkitemCache> cacheCaptor =
                ArgumentCaptor.forClass(YunxiaoWorkitemCache.class);
        verify(workitemCacheMapper).insert(cacheCaptor.capture());
        YunxiaoWorkitemCache cache = cacheCaptor.getValue();
        assertThat(cache.getYunxiaoProjectId()).isEqualTo("cloud-project-1");
        assertThat(cache.getCategory()).isEqualTo("Task");
        assertThat(cache.getStatus()).isEqualTo("修复中");
        assertThat(cache.getNormalizedStatus()).isEqualTo("IN_PROGRESS");
        assertThat(cache.getSourceCreatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 7, 40, 30));
        assertThat(cache.getSourceUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 10, 5, 52, 33));
        assertThat(cache.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(cache.getActive()).isEqualTo(1);
    }

    @Test
    void fullSyncStoresHistoricalItemsWithoutFetchingPerItemEffort() throws Exception {
        when(configService.getRuntimeConfig()).thenReturn(new YunxiaoRuntimeConfig(
                true, "center", "https://openapi-rdc.aliyuncs.com", "organization-id",
                "token", "PAGE", null, null, null));
        YunxiaoProjectMapping mapping = mapping(1L, 1L, "cloud-project-1");
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        when(client.searchWorkitems(eq("cloud-project-1"), eq("Req,Task,Bug"), isNull()))
                .thenReturn(List.of(objectMapper.readTree("""
                        {"id":"task-history-1","categoryId":"Task","subject":"历史任务"}
                        """)));

        assertThat(service.syncAll()).containsExactly("1:SUCCESS:1");

        ArgumentCaptor<YunxiaoWorkitemCache> cacheCaptor =
                ArgumentCaptor.forClass(YunxiaoWorkitemCache.class);
        verify(workitemCacheMapper).insert(cacheCaptor.capture());
        assertThat(cacheCaptor.getValue().getEstimatedHours()).isNull();
        assertThat(cacheCaptor.getValue().getActualHours()).isNull();
        verify(client, never()).listEstimatedEfforts("task-history-1");
        verify(client, never()).listEffortRecords("task-history-1");
        verify(effortRecordMapper, never()).delete(any());
        verify(estimatedEffortMapper, never()).delete(any());
        verify(workitemCacheMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void changingCloudProjectClearsDerivedDataAndPreviousSyncState() {
        Project project = new Project();
        project.setId(1L);
        when(projectMapper.selectById(1L)).thenReturn(project);

        YunxiaoProjectMapping existing = new YunxiaoProjectMapping();
        existing.setId(1L);
        existing.setProjectId(1L);
        existing.setYunxiaoProjectId("old-cloud-project");
        existing.setLastSyncStatus("SUCCESS");
        existing.setLastSyncError("old error");
        when(projectMappingMapper.selectOne(any())).thenReturn(existing);

        YunxiaoProjectMappingRequest request = new YunxiaoProjectMappingRequest();
        request.setProjectId(1L);
        request.setYunxiaoProjectId("new-cloud-project");
        request.setCategory("Req");
        request.setSyncEnabled(1);

        YunxiaoProjectMapping result = service.saveProjectMapping(request);

        assertThat(result.getYunxiaoProjectId()).isEqualTo("new-cloud-project");
        assertThat(result.getLastSyncStatus()).isNull();
        assertThat(result.getLastSyncError()).isNull();
        verify(workitemCacheMapper).delete(any());
        verify(effortRecordMapper).delete(any());
        verify(estimatedEffortMapper).delete(any());
        verify(projectMappingMapper).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void syncAllScansSharedCloudProjectOnceForAllLocalMappings() {
        when(configService.getRuntimeConfig()).thenReturn(new YunxiaoRuntimeConfig(
                true,
                "center",
                "https://openapi-rdc.aliyuncs.com",
                "organization-id",
                "token",
                "PAGE",
                null,
                null,
                null
        ));

        YunxiaoProjectMapping first = mapping(1L, 1L, "shared-cloud-project");
        YunxiaoProjectMapping second = mapping(2L, 2L, "shared-cloud-project");
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(first, second));
        when(client.searchWorkitems(
                eq("shared-cloud-project"),
                eq("Req,Task,Bug"),
                isNull()
        )).thenReturn(List.of());

        assertThat(service.syncAll()).containsExactly(
                "1:SUCCESS:0",
                "2:SUCCESS:0"
        );
        verify(client, times(1)).searchWorkitems(
                eq("shared-cloud-project"),
                eq("Req,Task,Bug"),
                isNull()
        );
        verify(projectMappingMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void syncAllUsesOneDayOverlapAfterPreviousSuccessfulSync() {
        when(configService.getRuntimeConfig()).thenReturn(new YunxiaoRuntimeConfig(
                true,
                "center",
                "https://openapi-rdc.aliyuncs.com",
                "organization-id",
                "token",
                "PAGE",
                null,
                null,
                null
        ));
        YunxiaoProjectMapping mapping = mapping(1L, 1L, "cloud-project");
        LocalDateTime previousSync = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(2);
        mapping.setLastSyncedAt(previousSync);
        mapping.setFullSyncedAt(previousSync.minusDays(10));
        mapping.setLastSyncStatus("SUCCESS");
        when(projectMappingMapper.selectList(any())).thenReturn(List.of(mapping));
        when(client.searchWorkitems(
                eq("cloud-project"),
                eq("Req,Task,Bug"),
                any(LocalDate.class)
        )).thenReturn(List.of());

        service.syncAll();

        ArgumentCaptor<LocalDate> modifiedSinceCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(client).searchWorkitems(
                eq("cloud-project"),
                eq("Req,Task,Bug"),
                modifiedSinceCaptor.capture()
        );
        assertThat(modifiedSinceCaptor.getValue())
                .isEqualTo(previousSync.toLocalDate().minusDays(1));
    }

    private YunxiaoProjectMapping mapping(Long id, Long projectId, String cloudProjectId) {
        YunxiaoProjectMapping mapping = new YunxiaoProjectMapping();
        mapping.setId(id);
        mapping.setProjectId(projectId);
        mapping.setYunxiaoProjectId(cloudProjectId);
        mapping.setCategory("Req,Task,Bug");
        mapping.setSyncEnabled(1);
        return mapping;
    }
}
