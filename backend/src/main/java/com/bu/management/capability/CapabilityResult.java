package com.bu.management.capability;

/**
 * 能力调用结果：成功带 dataJson，失败带错误码 + 人话。
 *
 * <p>刻意用 JSON 字符串而不是泛型对象：能力边界要跨进程（未来远端 Python worker），
 * 序列化格式必须在这一层就定死，否则本地调用和远端调用会长出两套结果语义。
 *
 * @param ok           是否成功
 * @param dataJson     成功时的结果 JSON 字符串
 * @param errorCode    失败错误码（UNKNOWN_CAPABILITY / GATE_DENIED / TIMEOUT / HANDLER_ERROR ...）
 * @param errorMessage 失败的人话说明，写库 / 回模型都要截断
 * @param latencyMs    耗时毫秒，由网关在返回前填写
 * @author BU Team
 * @since 2026-11-20
 */
public record CapabilityResult(
        boolean ok,
        String dataJson,
        String errorCode,
        String errorMessage,
        long latencyMs) {

    /** 成功结果，dataJson 为空时回落成空对象，避免调用方拿到 null 再分支。 */
    public static CapabilityResult ok(String dataJson) {
        return new CapabilityResult(true, dataJson == null ? "{}" : dataJson, null, null, 0L);
    }

    /** 失败结果：错误码必填，错误消息可为空但正常应给出人话。 */
    public static CapabilityResult fail(String errorCode, String errorMessage) {
        return new CapabilityResult(false, null, errorCode, errorMessage, 0L);
    }

    /** 回填耗时，返回新实例——结果对象在网关里可能需要被多处引用，不做可变字段。 */
    public CapabilityResult withLatency(long latencyMs) {
        return new CapabilityResult(ok, dataJson, errorCode, errorMessage, latencyMs);
    }
}
