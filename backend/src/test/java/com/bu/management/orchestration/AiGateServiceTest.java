package com.bu.management.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.AiGate;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.mapper.AiGateMapper;
import com.bu.management.mapper.AiRunMapper;
import com.bu.management.mapper.AiRunNodeMapper;
import com.bu.management.service.AiArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 人工闸门的契约：重复审批只生效一次、过期提交被拒、打回只动目标节点（上游证据保留）。
 */
@ExtendWith(MockitoExtension.class)
class AiGateServiceTest {

    @Mock private AiGateMapper gateMapper;
    @Mock private AiRunNodeMapper nodeMapper;
    @Mock private AiRunMapper runMapper;
    @Mock private AiArtifactService artifactService;

    private AiGateService service;

    @BeforeEach
    void setUp() {
        service = new AiGateService(gateMapper, nodeMapper, runMapper, artifactService);
    }

    @Test
    @DisplayName("通过：闸门置 APPROVED、节点置 SUCCEEDED、落 DECISION 制品")
    void approveOnce() {
        AiGate pending = gate(1L, 11L);
        AiGate done = gate(1L, 11L);
        done.setStatus(AiGateService.STATUS_APPROVED);
        when(gateMapper.selectById(1L)).thenReturn(pending, done);
        when(gateMapper.update(any(AiGate.class), any())).thenReturn(1);
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);
        when(runMapper.selectById(5L)).thenReturn(run(5L, 1L));
        AiArtifact artifact = new AiArtifact();
        artifact.setId(55L);
        when(artifactService.create(anyLong(), anyLong(), any())).thenReturn(artifact);

        AiGate result = service.approve(1L, 9L, "改得对", 1, 0);

        assertThat(result.getStatus()).isEqualTo(AiGateService.STATUS_APPROVED);
        assertThat(result.getArtifactId()).isEqualTo(55L);

        ArgumentCaptor<AiArtifactService.ArtifactCreateRequest> req =
                ArgumentCaptor.forClass(AiArtifactService.ArtifactCreateRequest.class);
        verify(artifactService).create(eq(9L), eq(1L), req.capture());
        assertThat(req.getValue().kind()).isEqualTo(AiArtifactService.KIND_DECISION);

        ArgumentCaptor<AiRunNode> nodePatch = ArgumentCaptor.forClass(AiRunNode.class);
        verify(nodeMapper).update(nodePatch.capture(), any());
        assertThat(nodePatch.getValue().getStatus()).isEqualTo(NodeStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("重复审批：第二次 CAS 影响 0 行，直接报错且不再改节点")
    void duplicateApproveRejected() {
        when(gateMapper.selectById(1L)).thenReturn(gate(1L, 11L));
        when(gateMapper.update(any(AiGate.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.approve(1L, 9L, "再批一次", 1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已被处理或已过期");

        verify(nodeMapper, never()).update(any(AiRunNode.class), any());
        verify(artifactService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("过期提交：期望 attempt 与闸门快照不一致时直接拒绝，不产生任何写入")
    void staleAttemptRejected() {
        AiGate pending = gate(1L, 11L);   // 快照 attempt=1
        when(gateMapper.selectById(1L)).thenReturn(pending);

        assertThatThrownBy(() -> service.approve(1L, 9L, "旧页面点的", 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交已过期：审批针对 attempt=1");

        verify(gateMapper, never()).update(any(AiGate.class), any());
        verify(nodeMapper, never()).update(any(AiRunNode.class), any());
    }

    @Test
    @DisplayName("不是指派给你的审批：拒绝")
    void wrongAssigneeRejected() {
        when(gateMapper.selectById(1L)).thenReturn(gate(1L, 11L));

        assertThatThrownBy(() -> service.approve(1L, 77L, "路过批一下", 1, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是指派给你的");
    }

    @Test
    @DisplayName("打回：只把目标与当前节点拉回 READY，上游节点与制品不动")
    void rejectReworksOnlyTargetNodes() {
        AiGate pending = gate(1L, 11L);
        AiGate done = gate(1L, 11L);
        done.setStatus(AiGateService.STATUS_REJECTED);
        when(gateMapper.selectById(1L)).thenReturn(pending, done);
        when(gateMapper.update(any(AiGate.class), any())).thenReturn(1);
        when(nodeMapper.selectById(11L)).thenReturn(node(11L, "review", NodeStatus.WAITING_GATE, 2));
        AiRunNode upstream = node(12L, "scan", NodeStatus.SUCCEEDED, 0);
        when(nodeMapper.selectOne(any())).thenReturn(upstream);
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);
        when(runMapper.selectById(5L)).thenReturn(run(5L, 1L));
        AiArtifact artifact = new AiArtifact();
        artifact.setId(56L);
        when(artifactService.create(anyLong(), anyLong(), any())).thenReturn(artifact);

        service.reject(1L, 9L, "口径不对，重出", "scan", 1, 0);

        ArgumentCaptor<AiRunNode> patches = ArgumentCaptor.forClass(AiRunNode.class);
        verify(nodeMapper, times(2)).update(patches.capture(), any());
        // 当前节点与打回目标都被置 READY，且各计一次 rework
        assertThat(patches.getAllValues()).allSatisfy(p ->
                assertThat(p.getStatus()).isEqualTo(NodeStatus.READY.name()));
        assertThat(patches.getAllValues()).allSatisfy(p -> assertThat(p.getReworkCount()).isNotNull());
        // 上游证据（制品）一个都没动
        verify(artifactService, never()).decide(anyLong(), anyLong(), any(Boolean.class), anyString());
    }

    @Test
    @DisplayName("重跑：闸门记 APPROVED_RETRY，节点回 READY")
    void retryPutsNodeBackToReady() {
        AiGate pending = gate(1L, 11L);
        AiGate done = gate(1L, 11L);
        done.setStatus(AiGateService.STATUS_APPROVED_RETRY);
        when(gateMapper.selectById(1L)).thenReturn(pending, done);
        when(gateMapper.update(any(AiGate.class), any())).thenReturn(1);
        when(nodeMapper.selectById(11L)).thenReturn(node(11L, "review", NodeStatus.WAITING_GATE, 1));
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);

        AiGate result = service.retry(1L, 9L, 1, 0);

        assertThat(result.getStatus()).isEqualTo(AiGateService.STATUS_APPROVED_RETRY);
        ArgumentCaptor<AiRunNode> patch = ArgumentCaptor.forClass(AiRunNode.class);
        verify(nodeMapper).update(patch.capture(), any());
        assertThat(patch.getValue().getStatus()).isEqualTo(NodeStatus.READY.name());
    }

    private static AiGate gate(Long id, Long nodeId) {
        AiGate gate = new AiGate();
        gate.setId(id);
        gate.setRunId(5L);
        gate.setNodeId(nodeId);
        gate.setStatus(AiGateService.STATUS_PENDING);
        gate.setAssigneeUserId(9L);
        gate.setRequestedFromStatus(NodeStatus.WAITING_GATE.name());
        gate.setRequestedAttempt(1);
        gate.setRequestedRework(0);
        return gate;
    }

    private static AiRunNode node(Long id, String key, NodeStatus status, int attempt) {
        AiRunNode node = new AiRunNode();
        node.setId(id);
        node.setRunId(5L);
        node.setNodeKey(key);
        node.setStatus(status.name());
        node.setAttempt(attempt);
        node.setReworkCount(0);
        return node;
    }

    private static AiRun run(Long id, Long projectId) {
        AiRun run = new AiRun();
        run.setId(id);
        run.setProjectId(projectId);
        return run;
    }
}
