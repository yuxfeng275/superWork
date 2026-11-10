package com.bu.management.plugin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * 工具入参读取口径：与 AiAgentToolService 里的同名私有方法逐字一致。
 *
 * <p>刻意复制而不是共用：旧 switch 是回退路径，必须保持可独立运行，
 * 两边一旦共用同一份实现，「回退」就退不回去了。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class ToolArgs {

    /** 与 AiAgentToolService.DEFAULT_LIMIT 一致。 */
    public static final int DEFAULT_LIMIT = 10;

    /** 与 AiAgentToolService.MAX_LIMIT 一致。 */
    public static final int MAX_LIMIT = 50;

    private ToolArgs() {
    }

    /** 取文本参数；非字符串一律当没传（与旧实现一致）。 */
    public static String text(JsonNode args, String name) {
        JsonNode node = path(args, name);
        return node.isTextual() ? node.asText() : null;
    }

    /** 取整型参数；不可转 int 时返回 null（与旧实现一致）。 */
    public static Integer integer(JsonNode args, String name) {
        JsonNode node = path(args, name);
        return node.canConvertToInt() ? node.asInt() : null;
    }

    /** limit 参数：缺省 10，夹在 1..50。 */
    public static int limit(JsonNode args) {
        JsonNode node = path(args, "limit");
        if (!node.canConvertToInt()) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(node.asInt(), 1), MAX_LIMIT);
    }

    private static JsonNode path(JsonNode args, String name) {
        return args == null ? MissingNode.getInstance() : args.path(name);
    }
}
