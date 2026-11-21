package com.bu.management.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.mapper.AiRunMapper;
import com.bu.management.mapper.AiRunNodeMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 编排内核：run 与节点的状态机 + 租约。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>幂等建 run</b>：靠 {@code uk_run_biz} 兜底，并发触发只会得到同一个 run；</li>
 *   <li><b>租约带代次</b>：接管时 epoch+1，所有提交都带期望 epoch，旧 worker 的写入影响 0 行并报错；</li>
 *   <li><b>迁移显式校验</b>：状态只能按 {@link RunStatus} / {@link NodeStatus} 允许的边走，非法迁移直接抛错。</li>
 * </ol>
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRunService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AiRunMapper runMapper;

    private final AiRunNodeMapper nodeMapper;

    /** 建 run 的入参；字段组合决定幂等键。 */
    public record CreateRun(String kind, String sopCode, int sopVersion, Long projectId,
                            Long ownerUserId, LocalDate bizDate, String triggerSource) {
    }

    /**
     * 幂等建 run：已存在（同 kind+sop+项目+身份+业务日）直接返回已有行。
     * 并发下靠唯一键兜底，捕获重复键后回查。
     */
    public AiRun createOrGet(CreateRun cmd) {
        AiRun existing = findExisting(cmd);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AiRun run = new AiRun();
        run.setKind(cmd.kind());
        run.setSopCode(cmd.sopCode());
        run.setSopVersion(cmd.sopVersion());
        run.setProjectId(cmd.projectId());
        run.setOwnerUserId(cmd.ownerUserId());
        run.setBizDate(cmd.bizDate() == null ? LocalDate.now() : cmd.bizDate());
        run.setTriggerSource(StringUtils.hasText(cmd.triggerSource()) ? cmd.triggerSource() : "API");
        run.setStatus(RunStatus.PENDING.name());
        run.setEpoch(0);
        run.setAttempt(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            AiRun raced = findExisting(cmd);
            if (raced == null) {
                throw e;
            }
            log.info("run 并发创建命中幂等键，复用已有 run id={}", raced.getId());
            return raced;
        }
        // runNo 需要 id：插入后回填，失败不影响主流程（只用于展示）
        String runNo = "NR-" + run.getBizDate().format(DAY) + "-"
                + String.format("%06d", run.getId() == null ? 0 : run.getId());
        AiRun patch = new AiRun();
        patch.setId(run.getId());
        patch.setRunNo(runNo);
        patch.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(patch);
        run.setRunNo(runNo);
        return run;
    }

    /**
     * 取得（或续）租约。
     *
     * @throws IllegalStateException 别人持有未过期的租约，或 epoch 已被他人推进
     */
    public AiRun claim(Long runId, String owner, Duration ttl) {
        AiRun run = require(runId);
        LocalDateTime now = LocalDateTime.now();
        boolean heldByOther = run.getLeaseExpiresAt() != null
                && run.getLeaseExpiresAt().isAfter(now)
                && !owner.equals(run.getLeaseOwner());
        if (heldByOther) {
            throw new IllegalStateException("run 正被 " + run.getLeaseOwner() + " 接管，稍后重试");
        }
        int expectedEpoch = run.getEpoch() == null ? 0 : run.getEpoch();
        RunStatus next = RunStatus.RUNNING;
        next.requireReachableFrom(RunStatus.valueOf(run.getStatus()));

        AiRun patch = new AiRun();
        patch.setId(runId);
        patch.setStatus(next.name());
        patch.setEpoch(expectedEpoch + 1);
        patch.setLeaseOwner(owner);
        patch.setLeaseExpiresAt(now.plus(ttl));
        patch.setStartedAt(run.getStartedAt() == null ? now : run.getStartedAt());
        patch.setUpdatedAt(now);
        int rows = runMapper.update(patch, Wrappers.<AiRun>lambdaUpdate()
                .eq(AiRun::getId, runId)
                .eq(AiRun::getEpoch, expectedEpoch));
        if (rows == 0) {
            throw new IllegalStateException("run 已被其他实例接管（epoch 已变更），旧 worker 放弃本次写入");
        }
        return require(runId);
    }

    /** 节点推进到 READY：调用方负责证明「所有前驱已 SUCCEEDED」。 */
    public void markNodeReady(Long nodeId, int epoch) {
        AiRunNode node = requireNode(nodeId);
        NodeStatus.READY.requireReachableFrom(NodeStatus.valueOf(node.getStatus()));
        updateNode(nodeId, epoch, patch -> {
            patch.setStatus(NodeStatus.READY.name());
            patch.setFinishedAt(null);
        });
    }

    /** 节点开跑：attempt+1，记录开始时间与执行器会话号。 */
    public AiRunNode startNode(Long nodeId, int epoch, String executorSessionId) {
        AiRunNode node = requireNode(nodeId);
        NodeStatus.RUNNING.requireReachableFrom(NodeStatus.valueOf(node.getStatus()));
        int attempt = (node.getAttempt() == null ? 0 : node.getAttempt()) + 1;
        AiRunNode patch = new AiRunNode();
        patch.setId(nodeId);
        patch.setStatus(NodeStatus.RUNNING.name());
        patch.setAttempt(attempt);
        patch.setExecutorSessionId(executorSessionId);
        patch.setStartedAt(LocalDateTime.now());
        patch.setFinishedAt(null);
        int rows = nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                .eq(AiRunNode::getId, nodeId)
                .eq(AiRunNode::getEpoch, epoch));
        if (rows == 0) {
            throw new IllegalStateException("节点 epoch 已变更，旧 worker 放弃本次写入：nodeId=" + nodeId);
        }
        return requireNode(nodeId);
    }

    /** 节点收尾：状态迁移与 epoch 都要过校验。 */
    public AiRunNode finishNode(Long nodeId, int epoch, NodeStatus status, String outputJson,
                                String errorCode, String errorMessage) {
        AiRunNode node = requireNode(nodeId);
        status.requireReachableFrom(NodeStatus.valueOf(node.getStatus()));
        AiRunNode patch = new AiRunNode();
        patch.setId(nodeId);
        patch.setStatus(status.name());
        patch.setOutputJson(outputJson);
        patch.setErrorCode(errorCode);
        patch.setErrorMessage(errorMessage);
        patch.setFinishedAt(LocalDateTime.now());
        int rows = nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                .eq(AiRunNode::getId, nodeId)
                .eq(AiRunNode::getEpoch, epoch));
        if (rows == 0) {
            throw new IllegalStateException("节点 epoch 已变更，旧 worker 放弃本次写入：nodeId=" + nodeId);
        }
        return requireNode(nodeId);
    }

    /** run 收尾；epoch 不匹配说明已被接管，本次结果丢弃。 */
    public AiRun finishRun(Long runId, int epoch, RunStatus status, String summary) {
        AiRun run = require(runId);
        status.requireReachableFrom(RunStatus.valueOf(run.getStatus()));
        AiRun patch = new AiRun();
        patch.setId(runId);
        patch.setStatus(status.name());
        patch.setSummary(summary);
        patch.setFinishedAt(status.isTerminal() ? LocalDateTime.now() : null);
        patch.setUpdatedAt(LocalDateTime.now());
        int rows = runMapper.update(patch, Wrappers.<AiRun>lambdaUpdate()
                .eq(AiRun::getId, runId)
                .eq(AiRun::getEpoch, epoch));
        if (rows == 0) {
            throw new IllegalStateException("run 已被接管，旧 worker 放弃本次收尾：runId=" + runId);
        }
        return require(runId);
    }

    public AiRun require(Long runId) {
        AiRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run 不存在：" + runId);
        }
        return run;
    }

    public AiRunNode requireNode(Long nodeId) {
        AiRunNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("节点不存在：" + nodeId);
        }
        return node;
    }

    private void updateNode(Long nodeId, int epoch, java.util.function.Consumer<AiRunNode> editor) {
        AiRunNode patch = new AiRunNode();
        patch.setId(nodeId);
        editor.accept(patch);
        int rows = nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                .eq(AiRunNode::getId, nodeId)
                .eq(AiRunNode::getEpoch, epoch));
        if (rows == 0) {
            throw new IllegalStateException("节点 epoch 已变更，旧 worker 放弃本次写入：nodeId=" + nodeId);
        }
    }

    private AiRun findExisting(CreateRun cmd) {
        // 注意：MySQL 的唯一索引对 NULL 不去重（NULL != NULL），所以 project_id 为空时
        // 必须显式用 IS NULL 回查，否则「不带项目」的 run 每次触发都会新建一条。
        var query = Wrappers.<AiRun>lambdaQuery()
                .eq(AiRun::getKind, cmd.kind())
                .eq(AiRun::getSopCode, cmd.sopCode())
                .eq(AiRun::getOwnerUserId, cmd.ownerUserId())
                .eq(AiRun::getBizDate, cmd.bizDate());
        if (cmd.projectId() == null) {
            query.isNull(AiRun::getProjectId);
        } else {
            query.eq(AiRun::getProjectId, cmd.projectId());
        }
        return runMapper.selectOne(query.last("limit 1"));
    }
}
