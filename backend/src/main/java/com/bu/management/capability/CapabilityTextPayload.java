package com.bu.management.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 文本型能力结果的编解码：让「注册表优先」与「旧 switch 兜底」两条路径对模型说的话逐字一致。
 *
 * <p>能力契约要求 {@link CapabilityResult#dataJson()} 是 JSON 字符串（未来要跨进程，格式必须在这一层定死），
 * 但助手工具里有一半工具的历史返回是给人看的中文文案。约定如下：
 * <ul>
 *   <li>文本型工具：{@link #of(String)} 把文案包成 JSON 字符串标量，{@link #unwrap(String)} 逐字还原</li>
 *   <li>结构化工具（project_facts / weekly_report_facts 等）：直接返回 JSON 对象 / 数组，
 *       {@link #unwrap(String)} 原样透传，一个字符都不动</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class CapabilityTextPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CapabilityTextPayload() {
    }

    /**
     * 文案 → JSON 字符串标量（合法 JSON，且能逐字还原）。
     *
     * @param text 工具文案
     * @return JSON 字符串字面量
     */
    public static String of(String text) {
        try {
            return MAPPER.writeValueAsString(text == null ? "" : text);
        } catch (Exception e) {
            throw new IllegalStateException("文本型能力结果序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * dataJson 是 JSON 字符串标量时还原成文案；是对象 / 数组 / 其它形态时原样返回。
     *
     * @param dataJson 能力结果里的 dataJson
     * @return 给模型的文本
     */
    public static String unwrap(String dataJson) {
        if (dataJson == null || dataJson.isEmpty() || dataJson.charAt(0) != '"') {
            return dataJson;
        }
        try {
            JsonNode node = MAPPER.readTree(dataJson);
            return node != null && node.isTextual() ? node.asText() : dataJson;
        } catch (Exception e) {
            return dataJson;
        }
    }
}
