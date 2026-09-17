package com.bu.management.constant;

import java.util.Locale;
import java.util.Set;

public final class YunxiaoWorkItemConstants {

    public static final String CATEGORY_REQUIREMENT = "Req";
    public static final String CATEGORY_TASK = "Task";
    public static final String CATEGORY_BUG = "Bug";
    public static final String EXECUTION_CATEGORIES = "Req,Task,Bug";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_OTHER = "OTHER";

    private static final Set<String> COMPLETED_TYPES = Set.of(
            "COMPLETED", "COMPLETE", "DONE", "CLOSED", "RESOLVED", "FINISHED");
    private static final Set<String> IN_PROGRESS_TYPES = Set.of(
            "IN_PROGRESS", "INPROGRESS", "DOING", "WORKING", "PROCESSING");
    private static final Set<String> PENDING_TYPES = Set.of(
            "PENDING", "TODO", "TO_DO", "NEW", "OPEN", "UNSTARTED");

    private YunxiaoWorkItemConstants() {
    }

    public static String normalizeStatus(String statusType, String displayName) {
        String type = normalize(statusType);
        if (COMPLETED_TYPES.contains(type)) {
            return STATUS_COMPLETED;
        }
        if (IN_PROGRESS_TYPES.contains(type)) {
            return STATUS_IN_PROGRESS;
        }
        if (PENDING_TYPES.contains(type)) {
            return STATUS_PENDING;
        }

        String display = normalize(displayName);
        if (display.startsWith("待") || display.contains("未开始") || display.contains("新建")) {
            return STATUS_PENDING;
        }
        if ((display.startsWith("已") && containsAny(display,
                "完成", "关闭", "验收", "上线", "解决", "发布", "测试", "拒绝"))
                || Set.of("关闭", "完成", "解决").contains(display)) {
            return STATUS_COMPLETED;
        }
        if (containsAny(display, "进行", "开发", "修复", "处理", "测试", "评审", "设计", "联调")) {
            return STATUS_IN_PROGRESS;
        }
        return STATUS_OTHER;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
