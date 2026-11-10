package com.bu.management.plugin.support;

import java.util.List;
import java.util.Map;

/**
 * 列表类工具的统一渲染：与 AiAgentToolService#render 逐字一致。
 *
 * <p>这段文案直接进模型上下文，少一个逗号、换一处措辞都会改变模型的解读，
 * 所以两条执行路径必须共用同一份「拼字符串」规则。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class ToolText {

    private ToolText() {
    }

    /** 渲染「当前用户的 X，共 N 条」以及逐行 key=value。 */
    public static String render(Long userId, String label, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "当前用户（ID=" + userId + "）暂无符合条件的" + label + "记录";
        }
        StringBuilder sb = new StringBuilder("当前用户（ID=" + userId + "）的" + label + "，共 "
                + rows.size() + " 条：");
        for (Map<String, Object> row : rows) {
            sb.append("\n- ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                if (!first) {
                    sb.append("，");
                }
                sb.append(entry.getKey()).append("=").append(value);
                first = false;
            }
        }
        return sb.toString();
    }
}
