package com.bu.management.annotation;

import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityRisk;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 能力声明注解：标在类上，声明「这个 Bean 是一个可被平台调用的能力」。
 *
 * <p>被标注的 Bean 必须同时实现 {@link com.bu.management.capability.CapabilityHandler}，
 * 否则 {@link com.bu.management.capability.CapabilityRegistry} 在启动期直接失败——
 * 宁可起不来，也不要线上出现「注册了但调不通」的半残能力。
 *
 * <p>这是能力契约的唯一入口：注解描述元数据，Handler 描述行为，注册表负责发现，
 * 网关负责门禁 / 超时 / 审计。调用方（如 AiAgentToolService）本阶段不改。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Capability {

    /** 能力编码，全局唯一。建议用「域.动作」形式，例如 ops.coach、skill.weekly。 */
    String id();

    /** 能力类别，决定网关走本地执行还是远端执行。默认工具类。 */
    CapabilityKind kind() default CapabilityKind.TOOL;

    /** 风险等级，WRITE 会经过 Jev 写操作门禁。默认只读，宁可手工标注写。 */
    CapabilityRisk risk() default CapabilityRisk.READ;

    /** 展示名，给运营 / 调试面板看。 */
    String title() default "";

    /** 何时该用这个能力，给模型做选择依据（会进入助手工具描述）。 */
    String whenToUse() default "";

    /** 入参 JSON Schema 字符串，默认空对象——注册表会校验它必须是合法 JSON 对象。 */
    String inputSchema() default "{}";

    /** 单次调用超时（毫秒），网关按它做 FutureTask 超时控制。 */
    int timeoutMs() default 30000;

    /** 是否幂等：非幂等能力在重试 / 队列重放时需谨慎，默认幂等。 */
    boolean idempotent() default true;
}
