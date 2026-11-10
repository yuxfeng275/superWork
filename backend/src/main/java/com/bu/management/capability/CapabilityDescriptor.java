package com.bu.management.capability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 能力的运行时描述符：注解 + Bean 名拍平后的不可变视图。
 *
 * <p>注册表在启动期一次性完成 schema 解析，之后所有读路径都拿这个 record，
 * 避免每次调用都去反射注解、重复解析 JSON Schema。
 *
 * @param id          能力编码，全局唯一
 * @param kind        能力类别
 * @param risk        风险等级（WRITE 走门禁）
 * @param title       展示名
 * @param whenToUse   何时该用（供模型选择）
 * @param inputSchema 已解析的 JSON Schema（保证是 JSON 对象）
 * @param timeoutMs   单次调用超时毫秒
 * @param idempotent  是否幂等
 * @param beanName    承载该能力的 Spring Bean 名，排查问题用
 * @author BU Team
 * @since 2026-11-20
 */
public record CapabilityDescriptor(
        String id,
        CapabilityKind kind,
        CapabilityRisk risk,
        String title,
        String whenToUse,
        JsonNode inputSchema,
        int timeoutMs,
        boolean idempotent,
        String beanName) {

    /** 是否为写操作能力（需要过 Jev 门禁）。 */
    public boolean isWrite() {
        return risk == CapabilityRisk.WRITE;
    }

    /** 是否为助手可直接调用的工具能力。 */
    public boolean isAgentTool() {
        return kind == CapabilityKind.TOOL;
    }
}
