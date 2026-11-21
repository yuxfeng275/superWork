package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.AiGate;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.AiGateMapper;
import com.bu.management.orchestration.AiGateService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 「待我确认」的契约：一个队列合并两类动作、低风险可批量、越权与过期逐条报告。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock private AiGateMapper gateMapper;
    @Mock private AiArtifactMapper artifactMapper;
    @Mock private AiGateService gateService;
    @Mock private AiArtifactService artifactService;
    @Mock private ProjectFactAccessGuard accessGuard;

    private ConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmationService(gateMapper, artifactMapper, gateService,
                artifactService, accessGuard);
    }

    @Test
    @DisplayName("队列：闸门 + 我可处理的制品，无权限的项目不出现")
    void queueMergesGatesAndArtifacts() {
        AiGate gate = new AiGate();
        gate.setId(3L);
        gate.setNodeId(11L);
        gate.setAssigneeUserId(9L);
        gate.setStatus(AiGateService.STATUS_PENDING);
        gate.setRequestedAttempt(1);
        gate.setRequestedRework(0);
        when(gateMapper.selectList(any())).thenReturn(List.of(gate));

        when(artifactMapper.selectList(any())).thenReturn(List.of(
                artifact(21L, 1L, AiArtifactService.KIND_PLAN),
                artifact(22L, 2L, AiArtifactService.KIND_PATCH)));
        when(accessGuard.canWrite(9L, 1L)).thenReturn(true);
        when(accessGuard.canWrite(9L, 2L)).thenReturn(false);

        ConfirmationService.Queue queue = service.queue(9L);

        assertThat(queue.gates()).hasSize(1);
        assertThat(queue.gates().get(0).expectedAttempt()).isEqualTo(1);
        // 项目 2 无写权限 → 不出现在队列里
        assertThat(queue.artifacts()).hasSize(1);
        assertThat(queue.artifacts().get(0).risk()).isEqualTo("LOW");
        assertThat(service.lowRiskCount(queue)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量通过：成功计数正确，越权的单条只进失败列表")
    void batchApproveReportsPerItemFailures() {
        when(accessGuard.canWrite(9L, 1L)).thenReturn(true);
        when(accessGuard.canWrite(9L, 2L)).thenReturn(false);
        when(artifactService.get(21L)).thenReturn(artifact(21L, 1L, AiArtifactService.KIND_PLAN));
        when(artifactService.get(22L)).thenReturn(artifact(22L, 2L, AiArtifactService.KIND_PATCH));

        ConfirmationService.BatchResult result = service.batch(9L,
                new ConfirmationService.BatchRequest(List.of(21L, 22L), List.of(), true, null));

        assertThat(result.approved()).isEqualTo(1);
        assertThat(result.failures()).anySatisfy(f -> assertThat(f).contains("制品 #22").contains("无权"));
    }

    @Test
    @DisplayName("批量打回必须写原因")
    void batchRejectRequiresReason() {
        assertThatThrownBy(() -> service.batch(9L,
                new ConfirmationService.BatchRequest(List.of(21L), List.of(), false, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须写原因");
    }

    @Test
    @DisplayName("闸门批量通过带上期望值，状态已变时只报这一条失败")
    void batchGateUsesExpectedValues() {
        when(gateService.approve(eq(3L), eq(9L), any(), eq(1), eq(0)))
                .thenReturn(new AiGate());
        when(gateService.approve(eq(4L), eq(9L), any(), eq(1), eq(0)))
                .thenThrow(new IllegalStateException("这张审批已被处理或已过期"));

        ConfirmationService.BatchResult result = service.batch(9L,
                new ConfirmationService.BatchRequest(List.of(),
                        List.of(new ConfirmationService.GateRef(3L, 1, 0),
                                new ConfirmationService.GateRef(4L, 1, 0)),
                        true, "批量通过"));

        assertThat(result.approved()).isEqualTo(1);
        assertThat(result.failures()).anySatisfy(f -> assertThat(f).contains("闸门 #4"));
    }

    private static AiArtifact artifact(Long id, Long projectId, String kind) {
        AiArtifact artifact = new AiArtifact();
        artifact.setId(id);
        artifact.setProjectId(projectId);
        artifact.setKind(kind);
        artifact.setTitle("制品 " + id);
        artifact.setStatus(AiArtifactService.STATUS_IN_REVIEW);
        artifact.setOwnerUserId(7L);
        return artifact;
    }
}
