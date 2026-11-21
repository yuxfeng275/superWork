package com.bu.management.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.AiGate;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.mapper.AiGateMapper;
import com.bu.management.mapper.AiRunMapper;
import com.bu.management.mapper.AiRunNodeMapper;
import com.bu.management.service.AiArtifactService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 人工闸门：approve / reject / retry / expire。
 *
 * <p>两个不可让步的规则：
 * <ol>
 *   <li><b>重复审批只生效一次</b>：所有决策都是「仅当 gate 仍 PENDING 且快照值与期望值一致」的条件更新，
 *       第二次提交影响 0 行并报错；不依赖前端按钮禁用。</li>
 *   <li><b>打回不销毁上游证据</b>：只把目标节点拉回 READY 并计 rework，上游节点的产物按 attempt 冻结保留。</li>
 * </ol>
 *
 * <p>决策本身落成 {@code ai_artifact(kind=DECISION)}，这样「谁在什么时候批了什么」进得了评审队列，
 * 而不是只留在一条会被人遗忘的状态字段里。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGateService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_APPROVED_RETRY = "APPROVED_RETRY";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private final AiGateMapper gateMapper;

    private final AiRunNodeMapper nodeMapper;

    private final AiRunMapper runMapper;

    private final AiArtifactService artifactService;

    /** 建闸门：节点进入 WAITING_GATE 后调用，快照用于后续乐观校验。 */
    public AiGate request(Long runId, Long nodeId, Long assigneeUserId, String question,
                          LocalDateTime dueAt) {
        AiRunNode node = requireNode(nodeId);
        AiGate gate = new AiGate();
        gate.setRunId(runId);
        gate.setNodeId(nodeId);
        gate.setStatus(STATUS_PENDING);
        gate.setAssigneeUserId(assigneeUserId);
        gate.setRequestedFromStatus(node.getStatus());
        gate.setRequestedAttempt(node.getAttempt() == null ? 0 : node.getAttempt());
        gate.setRequestedRework(node.getReworkCount() == null ? 0 : node.getReworkCount());
        gate.setQuestion(question);
        gate.setDueAt(dueAt);
        gate.setCreatedAt(LocalDateTime.now());
        gateMapper.insert(gate);
        return gate;
    }

    /** 通过：节点 WAITING_GATE → SUCCEEDED，并落决策制品。 */
    public AiGate approve(Long gateId, Long userId, String reason, int expectedAttempt,
                          int expectedRework) {
        AiGate gate = requireDecisionReady(gateId, userId, expectedAttempt, expectedRework);

        if (casGate(gateId, expectedAttempt, expectedRework,
                patch -> patch.setStatus(STATUS_APPROVED), reason) == 0) {
            throw new IllegalStateException("这张审批已被处理或已过期，请刷新后重试");
        }

        AiRunNode patch = new AiRunNode();
        patch.setStatus(NodeStatus.SUCCEEDED.name());
        patch.setFinishedAt(LocalDateTime.now());
        int rows = nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                .eq(AiRunNode::getId, gate.getNodeId())
                .eq(AiRunNode::getStatus, NodeStatus.WAITING_GATE.name()));
        if (rows == 0) {
            throw new IllegalStateException("节点已不在等待状态，无法通过：" + gate.getNodeId());
        }

        Long artifactId = writeDecisionArtifact(gate, userId, true, reason, null);
        AiGate done = gateMapper.selectById(gateId);
        done.setArtifactId(artifactId);
        return done;
    }

    /** 打回：节点回到 READY 并计 rework；{@code rejectTo} 指定时把目标节点也拉回 READY。 */
    public AiGate reject(Long gateId, Long userId, String reason, String rejectTo,
                         int expectedAttempt, int expectedRework) {
        AiGate gate = requireDecisionReady(gateId, userId, expectedAttempt, expectedRework);
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("打回必须写原因");
        }

        if (casGate(gateId, expectedAttempt, expectedRework,
                patch -> {
                    patch.setStatus(STATUS_REJECTED);
                    patch.setRejectTo(StringUtils.hasText(rejectTo) ? rejectTo.strip() : null);
                }, reason) == 0) {
            throw new IllegalStateException("这张审批已被处理或已过期，请刷新后重试");
        }

        AiRunNode current = requireNode(gate.getNodeId());
        rework(current, current.getAttempt() == null ? 0 : current.getAttempt());

        if (StringUtils.hasText(rejectTo)) {
            AiRunNode target = nodeMapper.selectOne(Wrappers.<AiRunNode>lambdaQuery()
                    .eq(AiRunNode::getRunId, gate.getRunId())
                    .eq(AiRunNode::getNodeKey, rejectTo.strip())
                    .last("limit 1"));
            if (target != null && !target.getId().equals(current.getId())) {
                // 上游节点可能是 SUCCEEDED：拉回 READY 只重置状态与 rework，产物按 attempt 冻结不动
                rework(target, 0);
            }
        }

        Long artifactId = writeDecisionArtifact(gate, userId, false, reason, rejectTo);
        AiGate done = gateMapper.selectById(gateId);
        done.setArtifactId(artifactId);
        return done;
    }

    /** 重跑本节点：不推进流程，只把节点放回 READY，attempt 由执行器下次开跑时 +1。 */
    public AiGate retry(Long gateId, Long userId, int expectedAttempt, int expectedRework) {
        AiGate gate = requireDecisionReady(gateId, userId, expectedAttempt, expectedRework);
        if (casGate(gateId, expectedAttempt, expectedRework,
                patch -> patch.setStatus(STATUS_APPROVED_RETRY), null) == 0) {
            throw new IllegalStateException("这张审批已被处理或已过期，请刷新后重试");
        }
        AiRunNode current = requireNode(gate.getNodeId());
        rework(current, current.getAttempt() == null ? 0 : current.getAttempt());
        return gateMapper.selectById(gateId);
    }

    /** 到期未处理：不自动放行，只标记并留给运行面。 */
    public void expire(Long gateId) {
        AiGate patch = new AiGate();
        patch.setStatus(STATUS_EXPIRED);
        int rows = gateMapper.update(patch, Wrappers.<AiGate>lambdaUpdate()
                .eq(AiGate::getId, gateId)
                .eq(AiGate::getStatus, STATUS_PENDING));
        if (rows == 0) {
            log.debug("审批已不是 PENDING，跳过过期标记：gateId={}", gateId);
        }
    }

    /**
     * 决策前置校验：必须在 PENDING、必须是本人、快照的 attempt/rework 要与调用方一致。
     *
     * <p>最后一条就是「过期提交被拒」：节点被重跑过之后，旧页面上的按钮不能再生效。
     */
    private AiGate requireDecisionReady(Long gateId, Long userId, int expectedAttempt,
                                        int expectedRework) {
        AiGate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new IllegalArgumentException("审批不存在：" + gateId);
        }
        if (!STATUS_PENDING.equals(gate.getStatus())) {
            throw new IllegalStateException("这张审批已处理（当前状态 " + gate.getStatus() + "）");
        }
        if (gate.getAssigneeUserId() != null && !gate.getAssigneeUserId().equals(userId)) {
            throw new IllegalStateException("这条审批不是指派给你的");
        }
        if (gate.getRequestedAttempt() != null && gate.getRequestedAttempt() != expectedAttempt) {
            throw new IllegalStateException("提交已过期：审批针对 attempt="
                    + gate.getRequestedAttempt() + "，当前是 " + expectedAttempt);
        }
        if (gate.getRequestedRework() != null && gate.getRequestedRework() != expectedRework) {
            throw new IllegalStateException("提交已过期：审批针对 rework="
                    + gate.getRequestedRework() + "，当前是 " + expectedRework);
        }
        return gate;
    }

    private int casGate(Long gateId, int expectedAttempt, int expectedRework,
                        java.util.function.Consumer<AiGate> editor, String decision) {
        AiGate patch = new AiGate();
        editor.accept(patch);
        patch.setDecidedAt(LocalDateTime.now());
        if (StringUtils.hasText(decision)) {
            patch.setDecision(decision);
        }
        return gateMapper.update(patch, Wrappers.<AiGate>lambdaUpdate()
                .eq(AiGate::getId, gateId)
                .eq(AiGate::getStatus, STATUS_PENDING)
                .eq(AiGate::getRequestedAttempt, expectedAttempt)
                .eq(AiGate::getRequestedRework, expectedRework));
    }

    /** 把节点放回 READY 并计 rework；用于打回与重跑。 */
    private void rework(AiRunNode node, int expectedAttempt) {
        AiRunNode patch = new AiRunNode();
        patch.setStatus(NodeStatus.READY.name());
        patch.setReworkCount((node.getReworkCount() == null ? 0 : node.getReworkCount()) + 1);
        patch.setFinishedAt(null);
        int rows = nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                .eq(AiRunNode::getId, node.getId())
                .eq(AiRunNode::getAttempt, expectedAttempt));
        if (rows == 0) {
            throw new IllegalStateException("节点已被重跑过，本次打回/重跑作废：" + node.getId());
        }
    }

    private Long writeDecisionArtifact(AiGate gate, Long userId, boolean approved, String reason,
                                       String rejectTo) {
        try {
            AiRun run = runMapper.selectById(gate.getRunId());
            if (run == null || run.getProjectId() == null) {
                // 不针对具体项目的 run 没有制品归属，决策只留在闸门表
                return null;
            }
            String content = "{\"gateId\":" + gate.getId()
                    + ",\"runId\":" + gate.getRunId()
                    + ",\"nodeId\":" + gate.getNodeId()
                    + ",\"approved\":" + approved
                    + ",\"reason\":\"" + safe(reason) + "\""
                    + ",\"rejectTo\":\"" + safe(rejectTo) + "\"}";
            AiArtifact artifact = artifactService.create(userId, run.getProjectId(),
                    new AiArtifactService.ArtifactCreateRequest(
                            (approved ? "闸门通过：" : "闸门打回：") + "节点 " + gate.getNodeId(),
                            AiArtifactService.KIND_DECISION, content, "[]", null, null,
                            run.getProjectId(), null));
            return artifact.getId();
        } catch (Exception e) {
            // 决策本身已经生效，制品落库失败不回滚审批，只记日志（运行面能看到 artifactId 为空）
            log.warn("决策制品落库失败 gateId={}：{}", gate.getId(), e.getMessage());
            return null;
        }
    }

    private AiRunNode requireNode(Long nodeId) {
        AiRunNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在：" + nodeId);
        }
        return node;
    }

    private static String safe(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "'");
    }
}
