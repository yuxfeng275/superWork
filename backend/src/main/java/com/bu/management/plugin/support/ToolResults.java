package com.bu.management.plugin.support;

import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityTextPayload;

/**
 * 工具类能力的标准返回：把「旧方法的返回值」翻译成能力结果，文案一个字都不改。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class ToolResults {

    /** 文本型工具的失败错误码：区别于网关自己的 GATE_DENIED / TIMEOUT 等。 */
    public static final String TOOL_ERROR = "TOOL_ERROR";

    private ToolResults() {
    }

    /** 成功 + 中文文案（包成 JSON 字符串标量，执行方会逐字还原）。 */
    public static CapabilityResult text(String content) {
        return CapabilityResult.ok(CapabilityTextPayload.of(content));
    }

    /** 失败 + 人话；errorMessage 就是旧方法当年返回的那句文案。 */
    public static CapabilityResult error(String message) {
        return CapabilityResult.fail(TOOL_ERROR, message);
    }

    /** 成功 + 结构化 JSON（工具本来返回的就是 JSON，原样交出去）。 */
    public static CapabilityResult json(String dataJson) {
        return CapabilityResult.ok(dataJson);
    }
}
