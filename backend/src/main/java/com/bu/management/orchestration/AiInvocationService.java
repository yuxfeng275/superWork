package com.bu.management.orchestration;

import com.bu.management.entity.AiInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 幂等与未知结果：工具调用的前置记录、逻辑动作键、恢复扫描。
 *
 * <p>「结果未知」不是一个装饰状态，而是这笔调用唯一诚实的描述：进程可能崩在
 * 记录之后、副作用之前，也可能崩在副作用之后、结果之前。系统无法区分，
 * 所以只能把它标成 {@link InvocationStatus#UNKNOWN}，并要求下一步先核对业务真实状态，
 * 而不是重发——重发才是重复写入的来源。
 *
 * <p>幂等键由「工具名 + 规范化参数」决定：参数对象按 key 排序后再哈希，
 * 同样的业务动作在重试里命中的是同一把键，而不是又写一次。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiInvocationService {

    private static final Set<String> STARTED = Set.of(InvocationStatus.STARTED.name());

    private static final Set<String> NOT_FINISHED = Set.of(
            InvocationStatus.STARTED.name(), InvocationStatus.UNKNOWN.name());

    /** 一次 begin 的入参。 */
    public record BeginCall(Long runId, Long nodeId, int attempt, String callId,
                            String toolName, String argsJson) {
    }

    /** begin 的结果：要么拿到新记录（可执行），要么命中幂等键（不可执行）。 */
    public record BeginResult(AiInvocation invocation, boolean replayable, boolean inFlight) {
    }

    private final InvocationStore store;

    private final ObjectMapper objectMapper;

    /**
     * 写入前置记录；副作用必须发生在它之后。
     *
     * <p>命中同一 {@code logical_action_key} 时：已有终态 → 可回放（不再执行）；
     * 仍是 STARTED → 视为同一动作在途，调用方应等待或标记未知，不得并行再发一次。
     */
    public BeginResult begin(BeginCall call) {
        String key = logicalActionKey(call.toolName(), call.argsJson(), call.attempt());
        AiInvocation existing = store.findByActionKey(call.runId(), key);
        if (existing != null) {
            return replayOrInFlight(existing);
        }
        AiInvocation row = new AiInvocation();
        row.setRunId(call.runId());
        row.setNodeId(call.nodeId());
        row.setAttempt(call.attempt());
        row.setCallId(call.callId());
        row.setLogicalActionKey(key);
        row.setToolName(call.toolName());
        row.setArgsJson(canonicalJson(call.argsJson()));
        row.setStatus(InvocationStatus.STARTED.name());
        row.setStartedAt(LocalDateTime.now());
        try {
            store.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发下两个 worker 同时发起同一动作：后到的一方按已有记录处理
            AiInvocation raced = store.findByActionKey(call.runId(), key);
            if (raced == null) {
                throw e;
            }
            return replayOrInFlight(raced);
        }
        return new BeginResult(row, false, false);
    }

    /** 记录成功结果；只有 STARTED 能改，重复提交影响 0 行即报错。 */
    public AiInvocation succeed(Long invocationId, String resultJson) {
        return transition(invocationId, STARTED, InvocationStatus.SUCCEEDED, resultJson, null);
    }

    /** 记录失败结果（业务明确失败，不是未知）。 */
    public AiInvocation fail(Long invocationId, String errorCode, String resultJson) {
        return transition(invocationId, STARTED, InvocationStatus.FAILED, resultJson, errorCode);
    }

    /** 核对后确认已生效但没记下结果：补写结果，状态记为 REPLAYED（可回放，不重发）。 */
    public AiInvocation resolveUnknownAsSucceeded(Long invocationId, String observedResultJson) {
        AiInvocation row = require(invocationId);
        if (InvocationStatus.SUCCEEDED.name().equals(row.getStatus())) {
            return row;
        }
        return transition(invocationId, NOT_FINISHED, InvocationStatus.REPLAYED,
                observedResultJson, null);
    }

    /** 核对后确认业务侧未生效：标记失败，允许下一轮重试。 */
    public AiInvocation resolveUnknownAsNotApplied(Long invocationId, String reason) {
        return transition(invocationId, NOT_FINISHED, InvocationStatus.FAILED, reason, "NOT_APPLIED");
    }

    /**
     * 恢复扫描：把「开始了但长时间没有结果」的调用标成 UNKNOWN。
     *
     * <p>只看 STARTED：终态不该被这条扫描碰到。返回被标记的行，调用方据此把节点置 UNKNOWN
     * 并在运行面提示「需要核对」。
     */
    public List<AiInvocation> markStaleAsUnknown(Duration olderThan) {
        LocalDateTime threshold = LocalDateTime.now().minus(olderThan);
        List<AiInvocation> stale = store.listStartedBefore(threshold);
        List<AiInvocation> marked = new ArrayList<>();
        for (AiInvocation row : stale) {
            AiInvocation patch = new AiInvocation();
            patch.setStatus(InvocationStatus.UNKNOWN.name());
            int rows = store.updateIfStatusIn(row.getId(), STARTED, patch);
            if (rows > 0) {
                row.setStatus(InvocationStatus.UNKNOWN.name());
                marked.add(row);
            }
        }
        if (!marked.isEmpty()) {
            log.warn("恢复扫描：{} 次调用结果未知，需核对后再决定重试", marked.size());
        }
        return marked;
    }

    public AiInvocation require(Long invocationId) {
        AiInvocation row = store.findById(invocationId);
        if (row == null) {
            throw new IllegalArgumentException("调用记录不存在：" + invocationId);
        }
        return row;
    }

    /**
     * 逻辑动作键：工具名 + 规范化参数 + 该节点的 attempt。
     *
     * <p>带上 attempt 是必须的：同一 attempt 内重复调用同一动作要合并（防止模型一次里写两遍），
     * 但跨 attempt 的重试是「人决定再来一次」，必须有新的键，否则核对后确认未生效的调用
     * 会因为旧键仍在而被永久挡住。
     */
    public String logicalActionKey(String toolName, String argsJson, int attempt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = (toolName + "|" + canonicalJson(argsJson) + "|a" + attempt)
                    .getBytes(StandardCharsets.UTF_8);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest(raw)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算幂等键：" + e.getMessage(), e);
        }
    }

    /** 规范化 JSON：对象 key 排序、去掉空白，保证「同样的动作」哈希一致。 */
    public String canonicalJson(String json) {
        if (!StringUtils.hasText(json)) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.writeValueAsString(sort(node));
        } catch (Exception e) {
            // 解析不了就把原文当字符串键，宁可不合并也不要抛错打断调用
            return "raw:" + json.strip();
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), sort(e.getValue()));
            }
            ObjectNode out = objectMapper.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = objectMapper.createArrayNode();
            arr.forEach(item -> out.add(sort(item)));
            return out;
        }
        return node;
    }

    private BeginResult replayOrInFlight(AiInvocation existing) {
        InvocationStatus status = InvocationStatus.valueOf(existing.getStatus());
        if (status == InvocationStatus.STARTED) {
            return new BeginResult(existing, false, true);
        }
        return new BeginResult(existing, true, false);
    }

    private AiInvocation transition(Long invocationId, Set<String> expected, InvocationStatus target,
                                    String resultJson, String errorCode) {
        require(invocationId);
        AiInvocation patch = new AiInvocation();
        patch.setStatus(target.name());
        patch.setResultJson(resultJson);
        patch.setErrorCode(errorCode);
        patch.setFinishedAt(LocalDateTime.now());
        int rows = store.updateIfStatusIn(invocationId, expected, patch);
        if (rows == 0) {
            throw new IllegalStateException("调用状态已变更，拒绝重复提交：" + invocationId);
        }
        return require(invocationId);
    }
}
