package com.bu.management.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.YunxiaoWorkitemLink;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoWorkitemLinkMapper;
import com.bu.management.vo.WorkItemOverviewItem;
import com.bu.management.vo.WorkItemOverviewQuery;
import com.bu.management.vo.WorkItemOverviewResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementOverviewServiceTest {

    @Mock private RequirementService requirementService;
    @Mock private YunxiaoWorkItemQueryService yunxiaoQueryService;
    @Mock private YunxiaoWorkitemLinkMapper workitemLinkMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private UserMapper userMapper;

    @InjectMocks private RequirementOverviewService service;

    @Test
    void linkedCloudRequirementIsMergedAndUnlinkedRequirementRemainsIndependent() {
        Requirement local = new Requirement();
        local.setId(1L);
        local.setReqNo("REQ-1");
        local.setTitle("客户数据驾驶舱");
        local.setProjectId(10L);
        local.setStatus("开发中");
        local.setPriority("高");
        local.setExpectedOnlineDate(java.time.LocalDate.now().minusDays(2));

        Page<Requirement> localPage = new Page<>(1, 10000);
        localPage.setRecords(List.of(local));
        when(requirementService.listWithPermission(
                eq(7L), eq("DIRECTOR"), eq(1), eq(10000),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(localPage);

        WorkItemOverviewItem linked = cloud("cloud-1", "YX-101", "客户数据驾驶舱");
        WorkItemOverviewItem unlinked = cloud("cloud-2", "YX-102", "移动端审批优化");
        unlinked.setDueDate(java.time.LocalDate.now().plusDays(2));
        when(yunxiaoQueryService.listCloudItems(
                eq("Req"), eq(7L), eq("DIRECTOR"), any(), any(), any(), any()
        )).thenReturn(List.of(linked, unlinked));

        YunxiaoWorkitemLink link = new YunxiaoWorkitemLink();
        link.setRequirementId(1L);
        link.setYunxiaoWorkitemId("cloud-1");
        link.setSerialNumber("YX-101");
        when(workitemLinkMapper.selectList(any())).thenReturn(List.of(link));
        com.bu.management.entity.Project project = new com.bu.management.entity.Project();
        project.setId(10L);
        project.setName("经营中台");
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project));

        WorkItemOverviewQuery query = new WorkItemOverviewQuery();
        query.setPage(1);
        query.setSize(20);
        WorkItemOverviewResponse result = service.getOverview(7L, "DIRECTOR", query);

        assertThat(result.getRecords()).hasSize(2);
        WorkItemOverviewItem localItem = result.getRecords().stream()
                .filter(item -> "local:1".equals(item.getRecordKey()))
                .findFirst().orElseThrow();
        assertThat(localItem.getLinkedYunxiaoWorkitemId()).isEqualTo("cloud-1");
        assertThat(localItem.getLinkedYunxiaoSerialNumber()).isEqualTo("YX-101");
        assertThat(result.getRecords()).anyMatch(item -> "yunxiao:cloud-2".equals(item.getRecordKey()));
        assertThat(result.getRecords()).noneMatch(item -> "yunxiao:cloud-1".equals(item.getRecordKey()));
        assertThat(result.getAnalysis().getStatusDistribution())
                .anySatisfy(row -> {
                    assertThat(row.getKey()).isEqualTo("IN_PROGRESS");
                    assertThat(row.getCount()).isEqualTo(2);
                });
        assertThat(result.getAnalysis().getSourceDistribution())
                .extracting("key", "count")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("LOCAL", 1L),
                        org.assertj.core.groups.Tuple.tuple("YUNXIAO", 1L));
        assertThat(result.getAnalysis().getProjectDistribution())
                .anySatisfy(row -> {
                    assertThat(row.getKey()).isEqualTo("10");
                    assertThat(row.getLabel()).isEqualTo("经营中台");
                    assertThat(row.getCount()).isEqualTo(1);
                });
        assertThat(result.getAnalysis().getPriorityDistribution())
                .anySatisfy(row -> {
                    assertThat(row.getKey()).isEqualTo("高");
                    assertThat(row.getCount()).isEqualTo(1);
                });
        assertThat(result.getAnalysis().getOverdueIncompleteCount()).isEqualTo(1);
        assertThat(result.getAnalysis().getOverdueProjectDistribution()).singleElement()
                .satisfies(row -> {
                    assertThat(row.getLabel()).isEqualTo("经营中台");
                    assertThat(row.getCount()).isEqualTo(1);
                });
        assertThat(result.getAnalysis().getMissingDueDateCount()).isZero();
    }

    private WorkItemOverviewItem cloud(String id, String serialNumber, String title) {
        WorkItemOverviewItem item = new WorkItemOverviewItem();
        item.setRecordKey("yunxiao:" + id);
        item.setDataSource("YUNXIAO");
        item.setReadOnly(true);
        item.setYunxiaoWorkitemId(id);
        item.setSerialNumber(serialNumber);
        item.setTitle(title);
        item.setStatus("开发中");
        item.setNormalizedStatus("IN_PROGRESS");
        return item;
    }
}
