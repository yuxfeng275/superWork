package com.bu.management.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.mapper.AiRunMapper;
import com.bu.management.mapper.AiRunNodeMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 编排内核算术：状态迁移必须合法、建 run 必须幂等、租约必须带代次。
 *
 * <p>这三条是「恢复后不重复写」的地基，所以先在最便宜的一层把它们钉住。
 */
@ExtendWith(MockitoExtension.class)
class AiRunServiceTest {

    @Mock private AiRunMapper runMapper;
    @Mock private AiRunNodeMapper nodeMapper;

    private AiRunService service;

    @BeforeEach
    void setUp() {
        service = new AiRunService(runMapper, nodeMapper);
    }

    @Test
    @DisplayName("run 状态迁移：终态不可回退，等人可回跑")
    void runTransitions() {
        assertThat(RunStatus.RUNNING.reachableFrom(RunStatus.PENDING)).isTrue();
        assertThat(RunStatus.WAITING_HUMAN.reachableFrom(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.RUNNING.reachableFrom(RunStatus.WAITING_HUMAN)).isTrue();
        assertThat(RunStatus.SUCCEEDED.reachableFrom(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.RUNNING.reachableFrom(RunStatus.SUCCEEDED)).isFalse();
        assertThat(RunStatus.PARTIAL.reachableFrom(RunStatus.CANCELLED)).isFalse();
        assertThatThrownBy(() -> RunStatus.RUNNING.requireReachableFrom(RunStatus.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的 run 状态迁移");
    }

    @Test
    @DisplayName("节点状态迁移：PENDING 必须先到 READY；SUCCEEDED 只能被 rework 拉回 READY")
    void nodeTransitions() {
        assertThat(NodeStatus.RUNNING.reachableFrom(NodeStatus.PENDING)).isFalse();
        assertThat(NodeStatus.READY.reachableFrom(NodeStatus.PENDING)).isTrue();
        assertThat(NodeStatus.RUNNING.reachableFrom(NodeStatus.READY)).isTrue();
        assertThat(NodeStatus.SUCCEEDED.reachableFrom(NodeStatus.RUNNING)).isTrue();
        assertThat(NodeStatus.READY.reachableFrom(NodeStatus.SUCCEEDED)).isTrue();
        assertThat(NodeStatus.WAITING_GATE.reachableFrom(NodeStatus.RUNNING)).isTrue();
        assertThat(NodeStatus.READY.reachableFrom(NodeStatus.UNKNOWN)).isTrue();
    }

    @Test
    @DisplayName("建 run 幂等：已有同键 run 时不再插入")
    void createRunIsIdempotent() {
        AiRun existing = run(9L, RunStatus.PENDING, 0);
        when(runMapper.selectOne(any())).thenReturn(existing);

        AiRun result = service.createOrGet(new AiRunService.CreateRun(
                "NIGHT_BATCH", "night-brief", 1, 1L, 7L, LocalDate.of(2026, 11, 22), "AUTOLOOP"));

        assertThat(result.getId()).isEqualTo(9L);
        verify(runMapper, never()).insert(any(AiRun.class));
    }

    @Test
    @DisplayName("新建 run：回填 runNo，状态 PENDING、epoch 0")
    void createRunAssignsRunNo() {
        when(runMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            AiRun row = inv.getArgument(0);
            row.setId(123L);
            return 1;
        }).when(runMapper).insert(any(AiRun.class));

        AiRun run = service.createOrGet(new AiRunService.CreateRun(
                "NIGHT_BATCH", "night-brief", 1, 1L, 7L, LocalDate.of(2026, 11, 22), "CRON"));

        assertThat(run.getRunNo()).startsWith("NR-20261122-");
        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING.name());
        assertThat(run.getEpoch()).isZero();
    }

    @Test
    @DisplayName("取租约：epoch+1，写入条件带旧 epoch")
    void claimIncrementsEpoch() {
        AiRun before = run(5L, RunStatus.PENDING, 0);
        AiRun after = run(5L, RunStatus.RUNNING, 1);
        when(runMapper.selectById(5L)).thenReturn(before, after);
        when(runMapper.update(any(AiRun.class), any())).thenReturn(1);

        AiRun claimed = service.claim(5L, "worker-a", Duration.ofMinutes(2));

        ArgumentCaptor<AiRun> patch = ArgumentCaptor.forClass(AiRun.class);
        verify(runMapper).update(patch.capture(), any());
        assertThat(patch.getValue().getEpoch()).isEqualTo(1);
        assertThat(patch.getValue().getLeaseOwner()).isEqualTo("worker-a");
        assertThat(claimed.getStatus()).isEqualTo(RunStatus.RUNNING.name());
    }

    @Test
    @DisplayName("取租约：别人持有未过期租约时拒绝")
    void claimRejectsHeldLease() {
        AiRun run = run(5L, RunStatus.RUNNING, 1);
        run.setLeaseOwner("worker-a");
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(runMapper.selectById(5L)).thenReturn(run);

        assertThatThrownBy(() -> service.claim(5L, "worker-b", Duration.ofMinutes(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正被 worker-a 接管");
    }

    @Test
    @DisplayName("取租约：epoch 已变（别人先接管）时本次写入影响 0 行并放弃")
    void claimFailsWhenEpochMoved() {
        AiRun run = run(5L, RunStatus.RUNNING, 3);
        when(runMapper.selectById(5L)).thenReturn(run);
        when(runMapper.update(any(AiRun.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.claim(5L, "worker-b", Duration.ofMinutes(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("epoch 已变更");
    }

    @Test
    @DisplayName("节点开跑：attempt+1，写会话号")
    void startNodeIncrementsAttempt() {
        AiRunNode node = node(11L, NodeStatus.READY, 1, 2);
        node.setAttempt(0);
        AiRunNode running = node(11L, NodeStatus.RUNNING, 1, 2);
        running.setAttempt(1);
        when(nodeMapper.selectById(11L)).thenReturn(node, running);
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);

        AiRunNode started = service.startNode(11L, 1, "sess-1");

        ArgumentCaptor<AiRunNode> patch = ArgumentCaptor.forClass(AiRunNode.class);
        verify(nodeMapper).update(patch.capture(), any());
        assertThat(patch.getValue().getAttempt()).isEqualTo(1);
        assertThat(patch.getValue().getExecutorSessionId()).isEqualTo("sess-1");
        assertThat(started.getStatus()).isEqualTo(NodeStatus.RUNNING.name());
    }

    @Test
    @DisplayName("节点非法迁移：PENDING 直接 RUNNING / 直接 SUCCEEDED 都拒绝")
    void illegalNodeTransitionsRejected() {
        AiRunNode pending = node(11L, NodeStatus.PENDING, 1, 2);
        when(nodeMapper.selectById(11L)).thenReturn(pending);

        assertThatThrownBy(() -> service.startNode(11L, 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的节点状态迁移");

        assertThatThrownBy(() -> service.finishNode(11L, 1, NodeStatus.SUCCEEDED, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法的节点状态迁移");
    }

    @Test
    @DisplayName("run 收尾：epoch 不匹配直接放弃")
    void finishRunRejectsStaleEpoch() {
        AiRun run = run(5L, RunStatus.RUNNING, 2);
        when(runMapper.selectById(5L)).thenReturn(run);
        when(runMapper.update(any(AiRun.class), any())).thenReturn(0);

        assertThatThrownBy(() -> service.finishRun(5L, 1, RunStatus.PARTIAL, "半个产出"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已被接管");
    }

    private static AiRun run(Long id, RunStatus status, int epoch) {
        AiRun run = new AiRun();
        run.setId(id);
        run.setStatus(status.name());
        run.setEpoch(epoch);
        run.setBizDate(LocalDate.of(2026, 11, 22));
        return run;
    }

    private static AiRunNode node(Long id, NodeStatus status, int epoch, int maxAttempt) {
        AiRunNode node = new AiRunNode();
        node.setId(id);
        node.setRunId(5L);
        node.setNodeKey("scan");
        node.setStatus(status.name());
        node.setEpoch(epoch);
        node.setMaxAttempt(maxAttempt);
        return node;
    }
}
