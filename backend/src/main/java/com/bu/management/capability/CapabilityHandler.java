package com.bu.management.capability;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 能力实现契约：一个 Bean 实现它 + 标 {@code @Capability} 即成为平台能力。
 *
 * <p>实现方只管业务，超时、门禁、审计、异常兜底全部由
 * {@link CapabilityGateway} 统一处理——这样每个能力不必各写一遍 try/catch 和日志表。
 *
 * <p>允许抛异常（历史服务方法大多如此），网关会把异常转成 HANDLER_ERROR 结果，
 * 绝不向上穿透打断调用链。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public interface CapabilityHandler {

    /**
     * 执行能力。
     *
     * @param input 入参 JSON，网关保证非 null（缺省为空对象）
     * @param ctx   调用上下文，网关保证非 null
     * @return 调用结果，不应返回 null
     * @throws Exception 允许透出异常，由网关统一兜底成失败结果
     */
    CapabilityResult execute(JsonNode input, CapabilityContext ctx) throws Exception;
}
