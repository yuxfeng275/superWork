package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiAgentUsage;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.mapper.AiAgentUsageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 调用可观测层：包一层 {@link DigestLlmClient#chat}，把「哪段提示词、哪个版本、耗时多少、
 * 成功还是失败」写进 {@code ai_agent_usage}，供看板做成本/耗时/失败率统计。
 *
 * <p>两条硬规矩：</p>
 * <ol>
 *   <li>可观测不能反咬主流程：落库失败只 log.warn，绝不影响 LLM 调用的返回值；</li>
 *   <li>异常原样抛出：这里只记录，不吞、不包装，业务侧的 catch 逻辑保持不变。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallRecorder {

    /**
     * 一次调用的观测元信息（与提示词/业务无关，只给落库用）。
     *
     * @param promptCode    提示词编码（ai_prompt.code），可空
     * @param promptVersion 提示词版本号（ai_prompt.version），可空
     * @param taskId        关联业务任务 ID（如周报 ID），可空
     * @param providerHint  期望的 provide 提示（失败时没有真实 provider，用它落库便于排查）
     */
    public record CallSpec(String promptCode, Integer promptVersion, Long taskId,
                           String providerHint) {}

    /**
     * 无会话调用时写入的 user_id 占位值：{@code ai_agent_usage.user_id} 是 NOT NULL，
     * 用 0 表示「平台侧 / 无登录会话的调用」，真实用户的用量汇总按 userId 过滤，不会把它算进去。
     */
    private static final long SYSTEM_USER_ID = 0L;

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final DigestLlmClient digestLlmClient;
    private final AiAgentUsageMapper usageMapper;

    /**
     * 带观测地调用一次 LLM：计时 → 调 DigestLlmClient → 落一行用量 → 返回；
     * 抛异常时先落一行 FAILED 再原样抛出。
     */
    public DigestLlmClient.ChatResult call(CallSpec spec, String systemPrompt, String userInput,
                                          Consumer<JsonNode> strictCheck) {
        CallSpec useSpec = spec == null ? new CallSpec(null, null, null, null) : spec;
        long startNanos = System.nanoTime();
        try {
            DigestLlmClient.ChatResult result = digestLlmClient.chat(systemPrompt, userInput, strictCheck);
            long latencyMs = elapsedMs(startNanos);
            // 成功行：provider/model 用真实返回值，回写让「实际用了哪个候选模型」一目了然
            persist(useSpec, STATUS_SUCCESS, latencyMs,
                    result == null ? null : result.providerCode(),
                    result == null ? null : result.model());
            return result;
        } catch (RuntimeException e) {
            long latencyMs = elapsedMs(startNanos);
            // 失败行：没有真实 provider/model，退回 providerHint；失败原因只进日志，不落库
            persist(useSpec, STATUS_FAILED, latencyMs, useSpec.providerHint(), null);
            log.warn("LLM 调用失败（已记录用量）prompt={} v{} task={} latency={}ms: {}",
                    useSpec.promptCode(), useSpec.promptVersion(), useSpec.taskId(), latencyMs,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    /**
     * 按日聚合：调用次数、失败数、平均耗时、token 与成本合计，给看板一张卡片用。
     *
     * <p>实现故意走 selectList + 内存聚合：跨库（MySQL/H2）都能跑，量级也就每天几千行。</p>
     */
    public Map<String, Object> dailyCostStats(LocalDate day) {
        LocalDate target = day == null ? LocalDate.now() : day;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("date", target.toString());
        stats.put("calls", 0L);
        stats.put("failures", 0L);
        stats.put("avgLatencyMs", 0L);
        stats.put("totalTokens", 0L);
        stats.put("totalCostUsd", BigDecimal.ZERO);
        try {
            LocalDateTime start = target.atStartOfDay();
            List<AiAgentUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<AiAgentUsage>()
                    .ge(AiAgentUsage::getCreatedAt, start)
                    .lt(AiAgentUsage::getCreatedAt, start.plusDays(1)));
            if (rows == null || rows.isEmpty()) {
                return stats;
            }
            long failures = 0;
            long latencySum = 0;
            long latencyCount = 0;
            long tokens = 0;
            BigDecimal cost = BigDecimal.ZERO;
            for (AiAgentUsage row : rows) {
                if (row == null) {
                    continue;
                }
                if (STATUS_FAILED.equalsIgnoreCase(row.getStatus())) {
                    failures++;
                }
                if (row.getLatencyMs() != null) {
                    latencySum += row.getLatencyMs();
                    latencyCount++;
                }
                if (row.getTotalTokens() != null) {
                    tokens += row.getTotalTokens();
                }
                if (row.getCostUsd() != null) {
                    cost = cost.add(row.getCostUsd());
                }
            }
            stats.put("calls", (long) rows.size());
            stats.put("failures", failures);
            stats.put("avgLatencyMs", latencyCount == 0 ? 0L : latencySum / latencyCount);
            stats.put("totalTokens", tokens);
            stats.put("totalCostUsd", cost);
            return stats;
        } catch (Exception e) {
            // 统计失败不抛：看板拿到 0 比拿到 500 好
            log.warn("AI 日用量聚合失败 day={}: {}", target, e.getMessage());
            return stats;
        }
    }

    /** 写一行用量；任何异常（表不存在、字段超长、连接池抖动）都只 warn，不影响主流程。 */
    private void persist(CallSpec spec, String status, long latencyMs, String provider,
                         String model) {
        try {
            AiAgentUsage row = new AiAgentUsage();
            // run_id 是表上的唯一键且 NOT NULL：每次调用生成一个短 UUID，长度 39 < VARCHAR(64)
            row.setRunId("prompt-" + UUID.randomUUID().toString().replace("-", ""));
            row.setUserId(SYSTEM_USER_ID);
            row.setProvider(StringUtils.hasText(provider) ? provider : spec.providerHint());
            row.setModel(model);
            row.setPromptCode(spec.promptCode());
            row.setPromptVersion(spec.promptVersion());
            row.setTaskId(spec.taskId());
            row.setLatencyMs(latencyMs);
            row.setStatus(status);
            row.setCreatedAt(LocalDateTime.now());
            // token / cost 字段不赋值：DigestLlmClient 只回传 JSON 与 provider/model，不解析 usage，
            // 拿不到真实用量。这些列在库里是 NOT NULL DEFAULT 0，不 set 就落 0（比落 NULL 更安全，
            // 相当于「本次未计量」而不是「计了 0 个 token」——看板需要区分时看 status 和 total_tokens 默认值）。
            usageMapper.insert(row);
        } catch (Exception e) {
            log.warn("AI 调用用量落库失败（不影响主流程）status={} prompt={} v{}: {}",
                    status, spec.promptCode(), spec.promptVersion(), e.getMessage());
        }
    }

    /** 纳秒转毫秒，保证非负（nanoTime 差值单调不减）。 */
    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
