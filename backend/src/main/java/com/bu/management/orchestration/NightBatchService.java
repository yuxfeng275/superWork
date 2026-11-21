package com.bu.management.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiRunNode;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.AiBrief;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.AiBriefMapper;
import com.bu.management.mapper.AiRunNodeMapper;
import com.bu.management.service.AutomationRunService;
import com.bu.management.service.ProjectFactService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 夜间批的最小 SOP：事实 → 制品 → 晨报。
 *
 * <p>这是「编排内核」第一次真正跑起来：run 由合拍建、节点按依赖推进、每个节点交一个执行器去干、
 * 失败不让整批归零。SOP 目前写在代码里（{@link #NODES}），等 {@code ai_sop_version} 的图编辑上线后
 * 再由配置驱动——先保证链路跑通，再谈可编辑。
 *
 * <p>三条硬规则落在实现里：
 * <ol>
 *   <li>没有事实就不派活（facts 节点为空 → 整个 run 记为 PARTIAL，不产出空制品）；</li>
 *   <li>单节点失败不影响兄弟节点（制品失败仍写晨报，让次日的人知道发生了什么）；</li>
 *   <li>产出口径由节点声明（PLAN / NIGHT_BRIEF），不再由服务层的公共提示词硬编码。</li>
 * </ol>
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NightBatchService {

    /** 夜间批固定用的 SOP 编码与版本。 */
    public static final String SOP_CODE = "night-brief";

    public static final int SOP_VERSION = 1;

    /** 节点定义：key + 执行器 + 产出模式 + 提示词。 */
    record NodeSpec(String key, int seq, String executorKind, AutomationRunService.Mode mode,
                    String title, String prompt) {
    }

    static final List<NodeSpec> NODES = List.of(
            new NodeSpec("facts", 1, "none", AutomationRunService.Mode.CHECK, "读取项目事实",
                    "读取目标项目最近 3 天的事实，列出可用于今晚产出的候选（每条给 factId）。"),
            new NodeSpec("draft", 2, "sidecar", AutomationRunService.Mode.PLAN, "产出待评审方案",
                    "基于本轮取到的事实，产出一份最值得推进的待评审方案（PLAN）。"
                            + "证据只允许来自 project_facts 返回的 factId；没有证据就不要产出。"),
            new NodeSpec("brief", 3, "sidecar", AutomationRunService.Mode.NIGHT_BRIEF, "写次日晨报",
                    "把今晚的结果写成给当前用户的晨报（最多 3 条，每条带 title/why/links）。"));

    private final AiRunService runService;

    private final AiRunNodeMapper nodeMapper;

    private final ProjectFactService factService;

    private final AutomationRunService automationRunService;

    private final AiArtifactMapper artifactMapper;

    private final AiBriefMapper briefMapper;

    /**
     * 触发一次夜间批（幂等）：同 kind+sop+项目+身份+业务日只跑一次。
     *
     * @param ownerUserId 以谁的身份跑（必须是真实成员）
     * @param bizDate     业务日，缺省今天
     */
    public AiRun run(Long ownerUserId, Long projectId, LocalDate bizDate, String triggerSource) {
        if (ownerUserId == null) {
            throw new IllegalArgumentException("ownerUserId 不能为空（夜间任务必须按成员身份运行）");
        }
        LocalDate day = bizDate == null ? LocalDate.now() : bizDate;
        AiRun run = runService.createOrGet(new AiRunService.CreateRun(
                "NIGHT_BATCH", SOP_CODE, SOP_VERSION, projectId, ownerUserId, day, triggerSource));
        if (RunStatus.SUCCEEDED.name().equals(run.getStatus())
                || RunStatus.PARTIAL.name().equals(run.getStatus())
                || RunStatus.RUNNING.name().equals(run.getStatus())) {
            // 已跑过或正在跑：直接返回，不再派活（幂等的下半句）
            log.info("夜间批已存在，跳过：runId={} status={}", run.getId(), run.getStatus());
            return run;
        }
        return execute(run, ownerUserId, projectId);
    }

    private AiRun execute(AiRun run, Long ownerUserId, Long projectId) {
        AiRun claimed = runService.claim(run.getId(), "night-batch", Duration.ofMinutes(30));
        int epoch = claimed.getEpoch() == null ? 0 : claimed.getEpoch();
        ensureNodes(run.getId(), epoch);

        boolean anyFailure = false;
        boolean factsEmpty = factService
                .query(projectId, null, LocalDateTime.now().minusDays(3), 1).isEmpty();

        for (NodeSpec spec : NODES) {
            AiRunNode node = node(run.getId(), spec.key());
            try {
                if (spec.seq() > 1 && factsEmpty) {
                    runService.markNodeReady(node.getId(), node.getEpoch());
                    runService.finishNode(node.getId(), node.getEpoch(), NodeStatus.FAILED,
                            null, "NO_FACTS", "项目近 3 天没有事实，按「无证据不派活」跳过");
                    anyFailure = true;
                    continue;
                }
                if ("brief".equals(spec.key()) && briefDelivered(ownerUserId, projectId,
                        LocalDate.now().plusDays(1))) {
                    runService.markNodeReady(node.getId(), node.getEpoch());
                    runService.finishNode(node.getId(), node.getEpoch(), NodeStatus.FAILED,
                            null, "BRIEF_LOCKED", "次日晨报已分发，夜间批不再覆盖");
                    anyFailure = true;
                    continue;
                }
                runService.markNodeReady(node.getId(), node.getEpoch());
                AiRunNode running = runService.startNode(node.getId(), node.getEpoch(),
                        node.getExecutorSessionId());
                String summary;
                if ("none".equals(spec.executorKind())) {
                    // 纯查询节点：不需要模型，直接给结论（也避免为「读事实」付一次模型钱）
                    summary = "事实条数=" + factService.query(projectId, null,
                            LocalDateTime.now().minusDays(3), 50).size();
                } else {
                    AutomationRunService.RunResult result = automationRunService.run(
                            ownerUserId, projectId, spec.title(), spec.prompt(), spec.mode(), null,
                            run.getId(), running.getId(),
                            running.getAttempt() == null ? 1 : running.getAttempt());
                    summary = truncate(result.text());
                    if (!result.ok()) {
                        throw new IllegalStateException(result.error());
                    }
                }
                if ("draft".equals(spec.key()) && !artifactSince(projectId, running.getStartedAt())) {
                    throw new IllegalStateException("节点没有产出任何制品（模型可能因缺少证据而放弃）");
                }
                if ("brief".equals(spec.key()) && !briefExists(ownerUserId, projectId,
                        LocalDate.now().plusDays(1))) {
                    throw new IllegalStateException("节点没有写出晨报");
                }
                runService.finishNode(running.getId(), running.getEpoch(), NodeStatus.SUCCEEDED,
                        "{\"summary\":\"" + escape(summary) + "\"}", null, null);
            } catch (Exception e) {
                anyFailure = true;
                log.warn("夜间批节点失败 run={} node={}：{}", run.getId(), spec.key(), e.getMessage());
                failQuietly(node.getId(), e.getMessage());
            }
        }

        RunStatus status = anyFailure ? RunStatus.PARTIAL : RunStatus.SUCCEEDED;
        return runService.finishRun(run.getId(), epoch, status,
                anyFailure ? "部分节点失败，产物已进评审队列" : "夜间批完成");
    }

    /** 建节点（幂等）：靠 uk_node 唯一键兜底，重复插入直接跳过。 */
    private void ensureNodes(Long runId, int epoch) {
        for (NodeSpec spec : NODES) {
            AiRunNode node = new AiRunNode();
            node.setRunId(runId);
            node.setNodeKey(spec.key());
            node.setSeq(spec.seq());
            node.setExecutorKind(spec.executorKind());
            node.setStatus(NodeStatus.PENDING.name());
            node.setAttempt(0);
            node.setMaxAttempt(2);
            node.setReworkCount(0);
            node.setEpoch(epoch);
            node.setDeps(spec.seq() == 1 ? "[]" : "[\"" + NODES.get(spec.seq() - 2).key() + "\"]");
            node.setGateRequired(0);
            try {
                nodeMapper.insert(node);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                log.debug("节点已存在，跳过：run={} node={}", runId, spec.key());
            }
        }
    }

    private AiRunNode node(Long runId, String key) {
        AiRunNode node = nodeMapper.selectOne(Wrappers.<AiRunNode>lambdaQuery()
                .eq(AiRunNode::getRunId, runId)
                .eq(AiRunNode::getNodeKey, key)
                .last("limit 1"));
        if (node == null) {
            throw new IllegalStateException("节点缺失：" + key);
        }
        return node;
    }

    /** 失败也要落状态：节点可能已经 RUNNING，直接改 FAILED；未开跑的置 FAILED 同样合法。 */
    private void failQuietly(Long nodeId, String message) {
        try {
            AiRunNode node = nodeMapper.selectById(nodeId);
            if (node == null || NodeStatus.FAILED.name().equals(node.getStatus())) {
                return;
            }
            AiRunNode patch = new AiRunNode();
            patch.setId(nodeId);
            patch.setStatus(NodeStatus.FAILED.name());
            patch.setErrorCode("NODE_FAILED");
            patch.setErrorMessage(truncate(message));
            patch.setFinishedAt(LocalDateTime.now());
            nodeMapper.update(patch, Wrappers.<AiRunNode>lambdaUpdate()
                    .eq(AiRunNode::getId, nodeId));
        } catch (Exception e) {
            log.error("节点状态落库失败 node={}：{}", nodeId, e.getMessage());
        }
    }

    /** 节点开始之后是否产出过制品：没有制品就是没交付，不能算成功。 */
    private boolean artifactSince(Long projectId, LocalDateTime since) {
        if (projectId == null || since == null) {
            return false;
        }
        Long count = artifactMapper.selectCount(Wrappers.<AiArtifact>lambdaQuery()
                .eq(AiArtifact::getProjectId, projectId)
                .ge(AiArtifact::getCreatedAt, since));
        return count != null && count > 0;
    }

    private boolean briefExists(Long userId, Long projectId, LocalDate date) {
        return findBrief(userId, projectId, date) != null;
    }

    private boolean briefDelivered(Long userId, Long projectId, LocalDate date) {
        AiBrief brief = findBrief(userId, projectId, date);
        return brief != null && brief.getDeliveredAt() != null;
    }

    private AiBrief findBrief(Long userId, Long projectId, LocalDate date) {
        if (projectId == null || date == null) {
            return null;
        }
        return briefMapper.selectOne(Wrappers.<AiBrief>lambdaQuery()
                .eq(AiBrief::getUserId, userId)
                .eq(AiBrief::getProjectId, projectId)
                .eq(AiBrief::getBriefDate, date)
                .last("limit 1"));
    }

    /** 给运行面用：某个 run 的节点列表。 */
    public List<AiRunNode> nodes(Long runId) {
        return nodeMapper.selectList(Wrappers.<AiRunNode>lambdaQuery()
                .eq(AiRunNode::getRunId, runId)
                .orderByAsc(AiRunNode::getSeq));
    }

    /** 事实候选（供运行面/调试看「今晚到底有什么料」）。 */
    public List<ProjectFact> candidates(Long projectId, int days, int limit) {
        return factService.query(projectId, null, LocalDateTime.now().minusDays(days), limit);
    }

    private static String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 500 ? flat.substring(0, 500) + "…" : flat;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "'");
    }
}
