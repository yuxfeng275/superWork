package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiAgentSession;
import com.bu.management.entity.AiAgentUsage;
import com.bu.management.mapper.AiAgentUsageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手用量：侧车 run_end 的 assistant 消息里带 usage（input/output/cache/totalTokens/cost），
 * 按 run 落库，供助手页展示近 30 天（滚动窗口）的次数与 token ——
 * `monthRuns`/`monthTokens` 就是这个滚动窗口，不是自然月，不要按自然月改语义。
 * 一轮里可能有多条 assistant 消息（工具调用循环），按 run 求和；run_id 幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentUsageService {

    /** 用户用量汇总：今日 / 近 30 天（滚动窗口，不是自然月）/ 累计 的次数、token 与成本。 */
    public record UsageSummary(long todayRuns, long todayTokens, long monthRuns, long monthTokens,
                               long totalRuns, long totalTokens, BigDecimal totalCostUsd) {}

    private final AiAgentUsageMapper usageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 记录一轮对话的用量；解析失败或没有 usage 时静默跳过，不影响对话主流程。
     */
    public void record(AiAgentSession session, String runId, String newMessagesJson) {
        if (session == null || !StringUtils.hasText(runId) || !StringUtils.hasText(newMessagesJson)) {
            return;
        }
        try {
            JsonNode messages = objectMapper.readTree(newMessagesJson);
            if (!messages.isArray()) {
                return;
            }
            long input = 0;
            long output = 0;
            long cacheRead = 0;
            long cacheWrite = 0;
            long total = 0;
            BigDecimal cost = BigDecimal.ZERO;
            String provider = null;
            String model = null;
            boolean hasUsage = false;
            for (JsonNode message : messages) {
                if (!"assistant".equals(message.path("role").asText())) {
                    continue;
                }
                if (StringUtils.hasText(message.path("provider").asText())) {
                    provider = message.path("provider").asText();
                }
                if (StringUtils.hasText(message.path("model").asText())) {
                    model = message.path("model").asText();
                }
                JsonNode usage = message.path("usage");
                if (usage.isMissingNode() || !usage.isObject()) {
                    continue;
                }
                hasUsage = true;
                input += usage.path("input").asLong(0);
                output += usage.path("output").asLong(0);
                cacheRead += usage.path("cacheRead").asLong(0);
                cacheWrite += usage.path("cacheWrite").asLong(0);
                total += usage.path("totalTokens").asLong(0);
                cost = cost.add(BigDecimal.valueOf(usage.path("cost").path("total").asDouble(0)));
            }
            if (!hasUsage) {
                return;
            }
            if (usageMapper.selectCount(new LambdaQueryWrapper<AiAgentUsage>()
                    .eq(AiAgentUsage::getRunId, runId)) > 0) {
                return;
            }
            AiAgentUsage row = new AiAgentUsage();
            row.setRunId(runId);
            row.setSessionId(session.getId());
            row.setUserId(session.getOwnerUserId());
            row.setProvider(StringUtils.hasText(provider) ? provider : session.getProvider());
            row.setModel(StringUtils.hasText(model) ? model : session.getModel());
            row.setInputTokens(input);
            row.setOutputTokens(output);
            row.setCacheReadTokens(cacheRead);
            row.setCacheWriteTokens(cacheWrite);
            row.setTotalTokens(total);
            row.setCostUsd(cost);
            row.setCreatedAt(LocalDateTime.now());
            usageMapper.insert(row);
        } catch (Exception e) {
            log.warn("AI 用量记录失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /** 当前用户用量汇总；monthRuns/monthTokens 是滚动 30 天，前端文案必须写「近 30 天」。 */
    public UsageSummary summary(Long userId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().minusDays(29).atStartOfDay();
        List<AiAgentUsage> rows = usageMapper.selectList(new LambdaQueryWrapper<AiAgentUsage>()
                .eq(AiAgentUsage::getUserId, userId));
        if (rows == null) {
            rows = List.of();
        }
        long todayRuns = 0;
        long todayTokens = 0;
        long monthRuns = 0;
        long monthTokens = 0;
        long totalTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (AiAgentUsage row : rows) {
            long tokens = row.getTotalTokens() == null ? 0 : row.getTotalTokens();
            totalTokens += tokens;
            if (row.getCostUsd() != null) {
                totalCost = totalCost.add(row.getCostUsd());
            }
            LocalDateTime at = row.getCreatedAt();
            if (at == null) {
                continue;
            }
            if (!at.isBefore(todayStart)) {
                todayRuns++;
                todayTokens += tokens;
            }
            if (!at.isBefore(monthStart)) {
                monthRuns++;
                monthTokens += tokens;
            }
        }
        return new UsageSummary(todayRuns, todayTokens, monthRuns, monthTokens,
                rows.size(), totalTokens, totalCost);
    }
}
