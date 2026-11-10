package com.bu.management.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型规划出来的一次执行计划：一组按顺序执行的能力调用。
 *
 * <p>解析刻意「严格 + 截断」：
 * <ul>
 *   <li>结构不对（不是对象 / steps 不是非空数组 / 某步没有 capabilityId / input 不是对象）
 *       一律抛 {@link IllegalArgumentException}——由调用方把它转成「该候选模型不合格」，
 *       让 {@code DigestLlmClient} 换下一个候选，而不是带着半截计划去执行；</li>
 *   <li>步数超过 {@link #MAX_STEPS} 直接截断：宁可少做，也不让一次对话跑出一条长链。</li>
 * </ul>
 *
 * <p>本类只做结构校验，不校验能力 id 是否存在（那需要注册表，属于编排器的事）。
 *
 * @param steps 计划步骤，非 null，长度 1..{@value #MAX_STEPS}
 * @author BU Team
 * @since 2026-11-21
 */
public record AgentPlan(List<AgentStep> steps) {

    /** 单个计划最多允许的步骤数：超过部分截断丢弃。 */
    public static final int MAX_STEPS = 4;

    public AgentPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * 单个步骤：调哪个能力、给什么入参、为什么调。
     *
     * @param capabilityId 能力编码，必须存在
     * @param input        入参 JSON 对象，模型没给时为空对象
     * @param reason       选择理由，给审计和排查用，可为空串
     */
    public record AgentStep(String capabilityId, JsonNode input, String reason) {

        public AgentStep {
            input = input == null || input.isNull()
                    ? JsonNodeFactory.instance.objectNode()
                    : input;
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * 按契约解析模型返回的 JSON，不合法即抛 {@link IllegalArgumentException}。
     *
     * @param raw 模型返回的 JSON 节点（可为 null，视为不合法）
     * @return 已截断到 {@link #MAX_STEPS} 步的计划
     * @throws IllegalArgumentException 结构不合法
     */
    public static AgentPlan parse(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("计划必须是 JSON 对象");
        }
        JsonNode stepsNode = raw.get("steps");
        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("计划缺少非空的 steps 数组");
        }
        List<AgentStep> parsed = new ArrayList<>();
        for (int i = 0; i < stepsNode.size() && parsed.size() < MAX_STEPS; i++) {
            JsonNode node = stepsNode.get(i);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("计划第 " + (i + 1) + " 步不是 JSON 对象");
            }
            String capabilityId = node.path("capabilityId").asText(null);
            if (capabilityId == null || capabilityId.isBlank()) {
                throw new IllegalArgumentException("计划第 " + (i + 1) + " 步缺少 capabilityId");
            }
            JsonNode input = node.get("input");
            if (input != null && !input.isNull() && !input.isObject()) {
                // input 必须是 JSON 对象：编排器要往里塞 _observation，非对象没法合并
                throw new IllegalArgumentException("计划第 " + (i + 1) + " 步的 input 必须是 JSON 对象");
            }
            parsed.add(new AgentStep(capabilityId.trim(), input, node.path("reason").asText("")));
        }
        return new AgentPlan(parsed);
    }
}
