package com.bu.management.agent;

import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityDescriptor;
import com.bu.management.capability.CapabilityGateway;
import com.bu.management.capability.CapabilityRegistry;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.service.AiCallRecorder;
import com.bu.management.service.PromptRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 任务型 Super Agent 的编排器骨架：先让模型规划（哪几个能力、什么顺序），再按顺序执行。
 *
 * <p>刻意的边界：
 * <ul>
 *   <li><b>不注册能力、不接业务链路</b>：本类只是编排，能跑什么完全取决于注册表里有什么；
 *       开关 {@code ai.agent.enabled} 默认 false，线上不打开就对现网零影响；</li>
 *   <li><b>只规划一次</b>：不让模型「边跑边改计划」（ReAct 式循环），一次规划最多
 *       {@value AgentPlan#MAX_STEPS} 步。理由是可预测：成本、延迟、步数都有上界，
 *       出问题时能按固定路径复盘；</li>
 *   <li><b>失败即停</b>：任何一步失败（写操作尤其）都立刻终止，不做自动重试。
 *       重试语义、补偿、对用户的解释，全部交回上层——编排器不该偷偷重放写操作；</li>
 *   <li><b>不再调 LLM 做汇总</b>：直接拼事实文本，省一次调用、少一层不确定性；</li>
 *   <li><b>不抛异常</b>：入口 {@link #run} 的契约是「永远给一个结果对象」，
 *       对话链路不该因为一次工具编排失败而 500。</li>
 * </ul>
 *
 * <p>执行完全走 {@link CapabilityGateway}：门禁、超时、审计都在网关里，
 * 这个类不碰 Handler，也不自己做任何权限判断。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Service
public class AgentOrchestrator {

    /** 规划提示词编码：可被 DB 里的同名 prompt 覆盖，未配置时用内置文案。 */
    static final String PLANNER_PROMPT_CODE = "agent.planner";

    /** 单轮总预算：规划 + 执行超过它就停，避免一次任务长时间占住调用线程。 */
    static final int BUDGET_MS = 120_000;

    /** 观察文本上限：带进下一步的上下文，宁短勿长。 */
    private static final int OBSERVATION_LIMIT = 500;

    /** 汇总里每步结果的摘录上限。 */
    private static final int SUMMARY_EXCERPT_LIMIT = 80;

    /** 日志里目标的截断上限。 */
    private static final int GOAL_LOG_LIMIT = 60;

    /** 内置规划提示词兜底文案：DB 没配也能work，且输出结构与 {@link AgentPlan#parse} 严格对齐。 */
    static final String FALLBACK_PLANNER_PROMPT = """
            你是任务规划器。给定用户目标和一份能力清单，输出一个 JSON 对象，格式：
            {"steps":[{"capabilityId":"能力id","input":{...},"reason":"为什么这一步"}],}

            硬性要求：
            1. 只能使用清单里出现过的 capabilityId，禁止编造；
            2. steps 是数组，1 到 4 步，按执行顺序排列；
            3. 能用一步完成的就不要拆成多步；不需要任何能力时也要给一步最接近的能力，不要返回空数组；
            4. input 必须是与该能力 inputSchema 匹配的 JSON 对象，缺省写 {}；
            5. 只输出 JSON，不要解释、不要 markdown 代码块。
            """;

    private final CapabilityRegistry registry;
    private final CapabilityGateway gateway;
    private final AiCallRecorder aiCallRecorder;
    private final PromptRegistry promptRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 总开关，默认 false。
     *
     * <p>用字段注入而不是构造参数，是因为它是「运行期灰度位」而不是依赖：
     * 构造器只收真正的协作者，开关缺失时按关闭处理，绝不会因为配置漏写而放行。
     */
    @Value("${ai.agent.enabled:false}")
    private boolean enabled = false;

    /**
     * 灰度名单（逗号分隔用户 ID）：只有名单里的人能触发编排入口。
     *
     * <p>为空表示「谁都不放行」——灰度开关打开但没填名单时，入口仍然关闭，
     * 避免一次误配就把任务编排暴露给所有人。
     */
    @Value("${ai.agent.gray-user-ids:}")
    private String grayUserIds = "";

    public AgentOrchestrator(CapabilityRegistry registry,
                             CapabilityGateway gateway,
                             AiCallRecorder aiCallRecorder,
                             PromptRegistry promptRegistry,
                             ObjectMapper objectMapper) {
        this.registry = registry;
        this.gateway = gateway;
        this.aiCallRecorder = aiCallRecorder;
        this.promptRegistry = promptRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 该用户是否已开通任务编排：全局开关 + 灰度名单双条件。
     *
     * @param userId 当前用户；null（系统调用）一律拒绝，灰度名额只给人
     */
    public boolean allow(Long userId) {
        if (!enabled || userId == null) {
            return false;
        }
        if (!StringUtils.hasText(grayUserIds)) {
            return false;
        }
        for (String part : grayUserIds.split(",")) {
            String value = part.strip();
            if (!value.isEmpty() && value.equals(String.valueOf(userId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 执行一次任务。
     *
     * @param goal 用户目标（自然语言）
     * @param ctx  调用上下文，null 按系统调用处理
     * @return 结果对象，永不为 null，永不抛异常
     */
    public AgentRunResult run(String goal, CapabilityContext ctx) {
        long startNanos = System.nanoTime();
        if (!enabled) {
            log.info("Agent 未启用，忽略本次请求（ai.agent.enabled=false）");
            return AgentRunResult.failed("AI Agent 未启用（ai.agent.enabled=false）",
                    elapsedMs(startNanos));
        }
        if (!StringUtils.hasText(goal)) {
            return AgentRunResult.failed("Agent 目标为空", elapsedMs(startNanos));
        }
        CapabilityContext context = ctx == null ? CapabilityContext.system() : ctx;
        try {
            AgentPlan plan = plan(goal, context);
            log.info("Agent 计划完成：steps={}, goal={}", plan.steps().size(),
                    abbreviate(goal, GOAL_LOG_LIMIT));
            return execute(plan, context, startNanos);
        } catch (RuntimeException e) {
            // 规划失败（无候选模型 / 所有候选都给了不合格的计划）/ 执行期意外，一律转结果
            log.warn("Agent 执行失败 goal={}: {}", abbreviate(goal, GOAL_LOG_LIMIT), message(e));
            return AgentRunResult.failed("Agent 执行失败：" + message(e), elapsedMs(startNanos));
        }
    }

    // ---------------------------------------------------------------- 规划

    /** 调模型做一次规划；strictCheck 保证「结构合法且能力都存在」才算这个候选合格。 */
    private AgentPlan plan(String goal, CapabilityContext ctx) {
        String systemPrompt = promptRegistry.get(PLANNER_PROMPT_CODE, FALLBACK_PLANNER_PROMPT);
        String userInput = buildPlannerInput(goal);
        DigestLlmClient.ChatResult result = aiCallRecorder.call(
                new AiCallRecorder.CallSpec(PLANNER_PROMPT_CODE, null, ctx.taskId(), null),
                systemPrompt, userInput, this::validatePlan);
        if (result == null || result.json() == null) {
            throw new IllegalStateException("规划模型没有返回计划 JSON");
        }
        return AgentPlan.parse(result.json());
    }

    /**
     * 规划结果的强校验：结构不合法或引用了未注册的能力就抛异常。
     *
     * <p>抛异常而不是「过滤掉非法步骤」是刻意的：过滤会悄悄改变用户的意图，
     * 让 {@code DigestLlmClient} 换下一个候选模型更符合预期。
     */
    private void validatePlan(JsonNode json) {
        AgentPlan plan = AgentPlan.parse(json);
        List<String> unknown = new ArrayList<>();
        for (AgentPlan.AgentStep step : plan.steps()) {
            if (registry.find(step.capabilityId()).isEmpty()) {
                unknown.add(step.capabilityId());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("计划引用了未注册的能力：" + String.join("、", unknown));
        }
    }

    /** 拼规划用的 user 输入：目标 + 可用能力清单（id / 名称 / 何时用 / schema）。 */
    private String buildPlannerInput(String goal) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户目标：").append(goal.trim()).append("\n\n可用能力清单：\n");
        List<CapabilityDescriptor> tools = registry.describeForAgent();
        if (tools == null || tools.isEmpty()) {
            sb.append("（当前没有任何可用能力）\n");
        } else {
            for (CapabilityDescriptor tool : tools) {
                sb.append("- id=").append(tool.id())
                        .append(" | 名称=").append(nullSafe(tool.title()))
                        .append(" | 何时用=").append(nullSafe(tool.whenToUse()))
                        .append(" | 风险=").append(tool.risk())
                        .append(" | inputSchema=")
                        .append(tool.inputSchema() == null ? "{}" : tool.inputSchema())
                        .append('\n');
            }
        }
        sb.append("\n最多 ").append(AgentPlan.MAX_STEPS).append(" 步，只使用上面列出的 id。");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 执行

    /** 顺序执行计划：上一步的结果（截断）塞进下一步 input 的 {@code _observation}。 */
    private AgentRunResult execute(AgentPlan plan, CapabilityContext ctx, long startNanos) {
        List<AgentPlan.AgentStep> steps = plan.steps();
        List<AgentRunResult.StepResult> results = new ArrayList<>(steps.size());
        String observation = null;
        String abortReason = null;

        for (int i = 0; i < steps.size(); i++) {
            if (i >= AgentPlan.MAX_STEPS) {
                abortReason = "步数超过上限 " + AgentPlan.MAX_STEPS;
                break;
            }
            if (elapsedMs(startNanos) > BUDGET_MS) {
                abortReason = "总耗时超过预算 " + BUDGET_MS + "ms";
                break;
            }
            AgentPlan.AgentStep step = steps.get(i);
            String capabilityId = step.capabilityId();
            JsonNode input = withObservation(step.input(), observation);
            CapabilityResult result = gateway.invoke(capabilityId, input, ctx);
            if (result == null) {
                result = CapabilityResult.fail("EMPTY_RESULT", "能力没有返回结果：" + capabilityId);
            }
            results.add(new AgentRunResult.StepResult(i, capabilityId, result.ok(),
                    result.ok() ? result.dataJson() : null, result.errorCode(), result.latencyMs()));
            observation = describeObservation(capabilityId, result);
            if (!result.ok()) {
                // 写操作失败尤其不能继续：后续步骤可能依赖它的结果，重放更是危险动作。
                boolean write = registry.find(capabilityId)
                        .map(CapabilityDescriptor::isWrite)
                        .orElse(false);
                abortReason = "第 " + (i + 1) + " 步失败（" + capabilityId
                        + (write ? "，写操作" : "") + "：" + nullSafe(result.errorCode())
                        + "），终止后续步骤";
                log.warn("Agent 执行中止：{}", abortReason);
                break;
            }
        }

        boolean ok = abortReason == null && results.size() == steps.size() && !results.isEmpty();
        return new AgentRunResult(ok, buildSummary(results, abortReason), results,
                elapsedMs(startNanos));
    }

    /** 把上一步的观察文本合并进本步 input；模型用不上也无害，字段名 {@code _observation}。 */
    private JsonNode withObservation(JsonNode input, String observation) {
        ObjectNode merged = input != null && input.isObject()
                ? ((ObjectNode) input).deepCopy()
                : objectMapper.createObjectNode();
        if (StringUtils.hasText(observation)) {
            merged.put("_observation", observation);
        }
        return merged;
    }

    /** 单步结果的观察文本：成功给数据、失败给错误码 + 人话，统一截断。 */
    private String describeObservation(String capabilityId, CapabilityResult result) {
        String text = result.ok()
                ? capabilityId + " 成功：" + nullSafe(result.dataJson())
                : capabilityId + " 失败[" + nullSafe(result.errorCode()) + "]："
                        + nullSafe(result.errorMessage());
        return abbreviate(text, OBSERVATION_LIMIT);
    }

    // ---------------------------------------------------------------- 汇总

    /** 不再调 LLM：把各步事实拼成人话，调用方要润色自己润色。 */
    private String buildSummary(List<AgentRunResult.StepResult> results, String abortReason) {
        int success = 0;
        for (AgentRunResult.StepResult result : results) {
            if (result.ok()) {
                success++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("执行 ").append(results.size()).append(" 步，成功 ").append(success).append(" 步：");
        for (AgentRunResult.StepResult result : results) {
            sb.append('\n').append(result.index() + 1).append(". ").append(result.capabilityId())
                    .append(" → ")
                    .append(result.ok()
                            ? abbreviate(result.dataJson(), SUMMARY_EXCERPT_LIMIT)
                            : "失败[" + nullSafe(result.errorCode()) + "]");
        }
        if (StringUtils.hasText(abortReason)) {
            sb.append('\n').append("终止原因：").append(abortReason);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 小工具

    /** 压成单行并截断：观察文本 / 汇总 / 日志都不适合塞多行长文本。 */
    private static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private static String message(Throwable e) {
        if (e == null) {
            return "";
        }
        return StringUtils.hasText(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
