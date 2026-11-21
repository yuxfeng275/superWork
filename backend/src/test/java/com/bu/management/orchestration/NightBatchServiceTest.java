package com.bu.management.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiBrief;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.AiInvocationMapper;
import com.bu.management.mapper.AiBriefMapper;
import com.bu.management.entity.Project;
import com.bu.management.mapper.AiRunNodeMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.service.AutomationRunService;
import com.bu.management.service.ProjectFactService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 夜间批 SOP 的行为契约：无事实不派活、单节点失败不拖垮整批、重复触发不重复执行。
 */
@ExtendWith(MockitoExtension.class)
class NightBatchServiceTest {

    @Mock private AiRunService runService;
    @Mock private AiRunNodeMapper nodeMapper;
    @Mock private ProjectFactService factService;
    @Mock private AutomationRunService automationRunService;
    @Mock private AiArtifactMapper artifactMapper;
    @Mock private AiBriefMapper briefMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private AiInvocationMapper invocationMapper;
    @Mock private AiGateService gateService;

    private NightBatchService service;

    @BeforeEach
    void setUp() {
        service = new NightBatchService(runService, nodeMapper, factService, automationRunService,
                artifactMapper, briefMapper, invocationMapper, projectMapper, gateService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "gateEnabled", false);
    }

    @Test
    @DisplayName("有事实：三个节点依次跑完，run 记 SUCCEEDED")
    void happyPathRunsAllNodes() {
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        stubNodesLifecycle();
        when(factService.query(anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenReturn(new AutomationRunService.RunResult("r1", 1L, "p", "m", "写好了", null, 2));
        when(artifactMapper.selectCount(any())).thenReturn(1L);
        when(briefMapper.selectOne(any())).thenReturn(null, brief());
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.SUCCEEDED, 1));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "AUTOLOOP");

        // facts 节点不调模型，另外两个节点各调一次
        verify(automationRunService, times(2)).run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt());
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("没有事实：制品与晨报节点记 NO_FACTS 失败，run 记 PARTIAL 且不调模型")
    void noFactsSkipsModelWork() {
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        stubNodesLifecycle();
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.PARTIAL, 1));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "CRON");

        verify(automationRunService, never()).run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt());
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.PARTIAL);
    }

    @Test
    @DisplayName("单个节点抛错：不影响后面的节点，run 记 PARTIAL")
    void nodeFailureDoesNotStopSiblings() {
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        stubNodesLifecycle();
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("模型超时"))
                .thenReturn(new AutomationRunService.RunResult("r2", 2L, "p", "m", "晨报写好了", null, 1));
        when(briefMapper.selectOne(any())).thenReturn(null, brief());
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.PARTIAL, 1));
        // 失败落库走 failQuietly：先查节点再直接改状态
        when(nodeMapper.selectById(anyLong())).thenReturn(node("draft", 2));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "API");

        // draft 抛错后，brief 仍然被尝试
        verify(automationRunService, times(2)).run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt());
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.PARTIAL);
        // 失败节点被显式置 FAILED
        verify(nodeMapper).update(any(AiRunNode.class), any());
    }

    @Test
    @DisplayName("重复触发：已完成的 run 直接返回，不再派活")
    void repeatedTriggerDoesNotRerun() {
        when(runService.createOrGet(any())).thenReturn(run(RunStatus.SUCCEEDED, 3));

        AiRun result = service.run(7L, 1L, LocalDate.of(2026, 11, 22), "CRON");

        assertThat(result.getStatus()).isEqualTo(RunStatus.SUCCEEDED.name());
        verify(runService, never()).claim(anyLong(), anyString(), any());
        verify(automationRunService, never()).run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt());
    }


    @Test
    @DisplayName("节点没产出制品：不算成功，run 记 PARTIAL（成功=有交付物）")
    void missingDeliverableIsNotSuccess() {
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        stubNodesLifecycle();
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenReturn(new AutomationRunService.RunResult("r1", 1L, "p", "m", "我没产出", null, 1));
        when(nodeMapper.selectById(anyLong())).thenReturn(node("draft", 2));
        // 制品表里没有新记录
        when(artifactMapper.selectCount(any())).thenReturn(0L);
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.PARTIAL, 1));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "API");

        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.PARTIAL);
    }


    @Test
    @DisplayName("开闸门：draft 产出后停在 WAITING_HUMAN，晨报节点不跑")
    void gateStopsBeforeBrief() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "gateEnabled", true);
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        stubNodesLifecycle();
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenReturn(new AutomationRunService.RunResult("r1", 1L, "p", "m", "方案已出", null, 1));
        when(artifactMapper.selectCount(any())).thenReturn(1L);
        Project project = new Project();
        project.setId(1L);
        project.setManagerId(9L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.WAITING_HUMAN, 1));
        when(runService.require(9L)).thenReturn(run(RunStatus.WAITING_HUMAN, 1));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "AUTOLOOP");

        // 只跑了 draft，晨报节点被闸门挡住
        verify(automationRunService, times(1)).run(anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), anyLong(), anyLong(), anyInt());
        verify(gateService).request(eq(9L), anyLong(), eq(9L), anyString(), any());
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.WAITING_HUMAN);
    }

    @Test
    @DisplayName("闸门通过后续跑：只补跑未完成的晨报节点并收尾")
    void resumeAfterGateRunsRemainingNodes() {
        AiRun waiting = run(RunStatus.WAITING_HUMAN, 1);
        AiRun running = run(RunStatus.RUNNING, 2);
        when(runService.require(9L)).thenReturn(waiting, run(RunStatus.SUCCEEDED, 2));
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(running);
        AiRunNode facts = node("facts", 1);
        facts.setStatus(NodeStatus.SUCCEEDED.name());
        AiRunNode draft = node("draft", 2);
        draft.setStatus(NodeStatus.SUCCEEDED.name());
        AiRunNode brief = node("brief", 3);
        when(nodeMapper.selectOne(any())).thenReturn(facts, draft, brief, brief);
        AiRunNode started = node("brief", 3);
        started.setStartedAt(java.time.LocalDateTime.now());
        started.setAttempt(1);
        when(runService.startNode(anyLong(), anyInt(), any())).thenReturn(started);
        when(runService.finishNode(anyLong(), anyInt(), any(), any(), any(), any()))
                .thenReturn(brief);
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenReturn(new AutomationRunService.RunResult("r3", 3L, "p", "m", "晨报写好了", null, 1));
        when(briefMapper.selectOne(any())).thenReturn(null, brief());
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.SUCCEEDED, 2));

        service.resumeAfterGate(9L);

        // 只补了晨报一个节点
        verify(automationRunService, times(1)).run(anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), anyLong(), anyLong(), anyInt());
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(2), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.SUCCEEDED);
    }


    @Test
    @DisplayName("有结果未知的调用：节点记 UNKNOWN（不是成功），run 记 PARTIAL")
    void unknownInvocationMarksNodeUnknown() {
        AiRun run = run(RunStatus.PENDING, 0);
        AiRun claimed = run(RunStatus.RUNNING, 1);
        when(runService.createOrGet(any())).thenReturn(run);
        when(runService.claim(eq(9L), anyString(), any())).thenReturn(claimed);
        stubNodeLookups();
        // 这个用例里 draft 直接转 UNKNOWN、brief 因缺晨报失败，所以只用到 startNode
        when(runService.startNode(anyLong(), anyInt(), any())).thenAnswer(inv -> {
            AiRunNode started = node("draft", 2);
            started.setStartedAt(java.time.LocalDateTime.now());
            return started;
        });
        when(factService.query(anyLong(), any(), any(), anyInt())).thenReturn(List.of(fact()));
        when(automationRunService.run(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyInt()))
                .thenReturn(new AutomationRunService.RunResult("r1", 1L, "p", "m", "试过了", null, 1));
        when(artifactMapper.selectCount(any())).thenReturn(1L);
        // draft 节点有一次调用停在 STARTED/UNKNOWN
        when(invocationMapper.selectCount(any())).thenReturn(1L);
        when(nodeMapper.update(any(AiRunNode.class), any())).thenReturn(1);
        when(runService.finishRun(anyLong(), anyInt(), any(), anyString()))
                .thenReturn(run(RunStatus.PARTIAL, 1));

        service.run(7L, 1L, LocalDate.of(2026, 11, 22), "API");

        ArgumentCaptor<AiRunNode> nodePatch = ArgumentCaptor.forClass(AiRunNode.class);
        verify(nodeMapper, org.mockito.Mockito.atLeastOnce()).update(nodePatch.capture(), any());
        assertThat(nodePatch.getAllValues()).anySatisfy(p ->
                assertThat(p.getStatus()).isEqualTo(NodeStatus.UNKNOWN.name()));
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        verify(runService).finishRun(eq(9L), eq(1), status.capture(), anyString());
        assertThat(status.getValue()).isEqualTo(RunStatus.PARTIAL);
    }

    private void stubNodeLookups() {
        when(nodeMapper.selectOne(any())).thenReturn(
                node("facts", 1), node("draft", 2), node("brief", 3),
                node("facts", 1), node("draft", 2), node("brief", 3));
    }

    private void stubNodesLifecycle() {
        when(runService.startNode(anyLong(), anyInt(), any())).thenAnswer(inv -> {
            AiRunNode started = node("draft", 2);
            started.setStartedAt(java.time.LocalDateTime.now());
            return started;
        });
        when(runService.finishNode(anyLong(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> node("brief", 3));
    }

    private static AiRun run(RunStatus status, int epoch) {
        AiRun run = new AiRun();
        run.setId(9L);
        run.setProjectId(1L);
        run.setOwnerUserId(7L);
        run.setStatus(status.name());
        run.setEpoch(epoch);
        return run;
    }

    private static AiRunNode node(String key, int seq) {
        AiRunNode node = new AiRunNode();
        node.setId((long) seq);
        node.setRunId(9L);
        node.setNodeKey(key);
        node.setSeq(seq);
        node.setStatus(NodeStatus.PENDING.name());
        node.setEpoch(1);
        node.setAttempt(0);
        node.setReworkCount(0);
        return node;
    }

    private static AiBrief brief() {
        AiBrief brief = new AiBrief();
        brief.setId(21L);
        brief.setUserId(9L);
        brief.setProjectId(1L);
        return brief;
    }

    private static ProjectFact fact() {
        ProjectFact fact = new ProjectFact();
        fact.setId(5L);
        fact.setProjectId(1L);
        return fact;
    }
}
