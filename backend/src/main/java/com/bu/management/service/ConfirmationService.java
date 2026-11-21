package com.bu.management.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.AiGate;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.AiGateMapper;
import com.bu.management.orchestration.AiGateService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 「待我确认」：把夜间产出的两类人工动作合并成一个队列，并且尽量少让人点。
 *
 * <p>参照 LoopForge 的 gate 思路，但按合拍的实际使用做了三处收敛：
 * <ol>
 *   <li><b>一个入口</b>：节点闸门（ai_gate）和制品评审（ai_artifact IN_REVIEW）本来是两个队列，
 *       人得记着去两个地方看——实测 39 份制品堆在评审里没人动，就是这么堆起来的；</li>
 *   <li><b>批量处理</b>：低风险（方案/修复说明/清单/决策记录）可以一次全过，
 *       不需要逐条点开；高风险（补丁类）仍然单条确认；</li>
 *   <li><b>失败不吞</b>：批量里每一条独立判定，被拒的写清原因（不是本人产出、状态已变、
 *       权限不足），前端能逐条看到哪一条没成。</li>
 * </ol>
 *
 * <p>刻意不做「自动批准」：夜间只允许可回滚写，但「人确认」是这条链路的价值所在；
 * 靠批量把成本压到一次点击，比放开自动批准更安全。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationService {

    /** 低风险类型：可以批量通过。 */
    private static final Set<String> LOW_RISK_KINDS = Set.of(
            AiArtifactService.KIND_PLAN,
            AiArtifactService.KIND_FIX_NOTE,
            AiArtifactService.KIND_CHECKLIST,
            AiArtifactService.KIND_DECISION);

    /** 中等风险：需要单条看，批量按钮不带它。 */
    private static final Set<String> MEDIUM_RISK_KINDS = Set.of(AiArtifactService.KIND_PATCH);

    private final AiGateMapper gateMapper;

    private final AiArtifactMapper artifactMapper;

    private final AiGateService gateService;

    private final AiArtifactService artifactService;

    private final ProjectFactAccessGuard accessGuard;

    /** 待确认队列里的一条：闸门或制品。 */
    public record Item(String type, Long id, Long projectId, String title, String detail,
                       String risk, Integer expectedAttempt, Integer expectedRework,
                       LocalDateTime createdAt) {
    }

    /** 队列：先闸门（阻塞夜间批）后制品（不阻塞但会被忘）。 */
    public record Queue(List<Item> gates, List<Item> artifacts) {
        public int total() {
            return gates.size() + artifacts.size();
        }
    }

    /** 批量结果：成功数与逐条失败原因。 */
    public record BatchResult(int approved, List<String> failures) {
    }

    /** 一条待确认的闸门（带乐观校验期望值）。 */
    public record GateRef(Long id, int expectedAttempt, int expectedRework) {
    }

    /** 批量请求。 */
    public record BatchRequest(List<Long> artifactIds, List<GateRef> gates, boolean approve,
                               String reason) {
    }

    /** 我的待确认：闸门按指派给自己，制品按「评审中 + 自己能改这个项目 + 不是自己产出的」。 */
    public Queue queue(Long userId) {
        List<Item> gates = new ArrayList<>();
        for (AiGate gate : gateMapper.selectList(Wrappers.<AiGate>lambdaQuery()
                .eq(AiGate::getAssigneeUserId, userId)
                .eq(AiGate::getStatus, AiGateService.STATUS_PENDING)
                .orderByAsc(AiGate::getCreatedAt)
                .last("limit 100"))) {
            gates.add(new Item("GATE", gate.getId(), null, "夜间批等待确认",
                    StringUtils.hasText(gate.getQuestion()) ? gate.getQuestion()
                            : "节点 " + gate.getNodeId() + " 等待放行",
                    "HIGH", gate.getRequestedAttempt(), gate.getRequestedRework(),
                    gate.getCreatedAt()));
        }

        List<Item> artifacts = new ArrayList<>();
        for (AiArtifact artifact : artifactMapper.selectList(Wrappers.<AiArtifact>lambdaQuery()
                .eq(AiArtifact::getStatus, AiArtifactService.STATUS_IN_REVIEW)
                .ne(AiArtifact::getOwnerUserId, userId)
                .orderByDesc(AiArtifact::getId)
                .last("limit 100"))) {
            if (!accessGuard.canWrite(userId, artifact.getProjectId())) {
                continue;
            }
            artifacts.add(new Item("ARTIFACT", artifact.getId(), artifact.getProjectId(),
                    artifact.getTitle(), truncate(artifact.getContent()), riskOf(artifact.getKind()),
                    null, null, artifact.getCreatedAt()));
        }
        return new Queue(gates, artifacts);
    }

    /**
     * 批量确认：逐条独立执行，任何一条失败不影响其他条。
     *
     * <p>批量的意义是「一次点击处理掉低风险项」，所以不做整体事务——
     * 一条状态已变就该跳过它，而不是把已经成功的十条回滚。
     */
    public BatchResult batch(Long userId, BatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (!request.approve() && !StringUtils.hasText(request.reason())) {
            throw new IllegalArgumentException("批量打回必须写原因");
        }
        int ok = 0;
        List<String> failures = new ArrayList<>();

        if (request.artifactIds() != null) {
            for (Long artifactId : request.artifactIds()) {
                try {
                    AiArtifact artifact = artifactService.get(artifactId);
                    if (!accessGuard.canWrite(userId, artifact.getProjectId())) {
                        failures.add("制品 #" + artifactId + "：无权处理该项目");
                        continue;
                    }
                    artifactService.decide(userId, artifactId, request.approve(), request.reason());
                    ok++;
                } catch (RuntimeException e) {
                    failures.add("制品 #" + artifactId + "：" + e.getMessage());
                }
            }
        }

        if (request.gates() != null) {
            for (GateRef ref : request.gates()) {
                try {
                    if (request.approve()) {
                        gateService.approve(ref.id(), userId, request.reason(),
                                ref.expectedAttempt(), ref.expectedRework());
                    } else {
                        gateService.reject(ref.id(), userId, request.reason(), null,
                                ref.expectedAttempt(), ref.expectedRework());
                    }
                    ok++;
                } catch (RuntimeException e) {
                    failures.add("闸门 #" + ref.id() + "：" + e.getMessage());
                }
            }
        }

        log.info("待我确认：用户 {} 批量{}，成功 {}，失败 {}", userId,
                request.approve() ? "通过" : "打回", ok, failures.size());
        return new BatchResult(ok, failures);
    }

    /** 低风险条数：前端据此决定「一键通过」按钮是否可用。 */
    public int lowRiskCount(Queue queue) {
        return (int) queue.artifacts().stream().filter(item -> "LOW".equals(item.risk())).count();
    }

    private static String riskOf(String kind) {
        if (LOW_RISK_KINDS.contains(kind)) {
            return "LOW";
        }
        if (MEDIUM_RISK_KINDS.contains(kind)) {
            return "MEDIUM";
        }
        return "MEDIUM";
    }

    private static String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
