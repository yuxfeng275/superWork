package com.bu.management.service;

import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.vo.WorkItemAnalysis;
import com.bu.management.vo.WorkItemDistributionItem;
import com.bu.management.vo.WorkItemOverviewItem;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class WorkItemAnalysisBuilder {

    private static final Map<String, String> STATUS_LABELS = Map.of(
            YunxiaoWorkItemConstants.STATUS_PENDING, "待处理",
            YunxiaoWorkItemConstants.STATUS_IN_PROGRESS, "进行中",
            YunxiaoWorkItemConstants.STATUS_COMPLETED, "已完成",
            YunxiaoWorkItemConstants.STATUS_OTHER, "其他");

    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "LOCAL", "本地",
            "YUNXIAO", "云效");

    private WorkItemAnalysisBuilder() {
    }

    public static WorkItemAnalysis build(List<? extends WorkItemOverviewItem> items) {
        WorkItemAnalysis analysis = new WorkItemAnalysis();
        analysis.setStatusDistribution(distribution(items,
                item -> defaultValue(item.getNormalizedStatus(), YunxiaoWorkItemConstants.STATUS_OTHER),
                key -> STATUS_LABELS.getOrDefault(key, key)));
        analysis.setProjectDistribution(projectDistribution(items));
        analysis.setOwnerDistribution(distribution(items,
                item -> item.getAssigneeId() == null
                        ? "unassigned:" + defaultValue(item.getAssigneeName(), "未分配")
                        : String.valueOf(item.getAssigneeId()),
                key -> ownerLabel(items, key)));
        analysis.setSourceDistribution(distribution(items,
                item -> defaultValue(item.getDataSource(), "UNKNOWN"),
                key -> SOURCE_LABELS.getOrDefault(key, key)));
        analysis.setPriorityDistribution(distribution(items,
                item -> defaultValue(item.getPriority(), "未设置"), Function.identity()));
        analysis.setTotalEstimatedHours(sumHours(items, WorkItemOverviewItem::getEstimatedHours));
        analysis.setTotalActualHours(sumHours(items, WorkItemOverviewItem::getActualHours));
        analysis.setUnassignedCount(items.stream()
                .filter(item -> item.getAssigneeId() == null && !StringUtils.hasText(item.getAssigneeName()))
                .count());
        List<? extends WorkItemOverviewItem> overdueItems = items.stream()
                .filter(WorkItemOverviewItem::isOverdueIncomplete)
                .toList();
        analysis.setOverdueIncompleteCount(overdueItems.size());
        analysis.setMissingDueDateCount(items.stream().filter(item -> item.getDueDate() == null).count());
        analysis.setOverdueProjectDistribution(projectDistribution(overdueItems));
        analysis.setOverdueOwnerDistribution(distribution(overdueItems,
                item -> item.getAssigneeId() == null
                        ? "unassigned:" + defaultValue(item.getAssigneeName(), "未分配")
                        : String.valueOf(item.getAssigneeId()),
                key -> ownerLabel(overdueItems, key)));
        analysis.setOverdueAgeDistribution(distribution(overdueItems,
                WorkItemAnalysisBuilder::overdueAgeKey,
                WorkItemAnalysisBuilder::overdueAgeLabel));
        long completed = items.stream()
                .filter(item -> YunxiaoWorkItemConstants.STATUS_COMPLETED.equals(item.getNormalizedStatus()))
                .count();
        analysis.setCompletionRate(items.isEmpty() ? 0
                : BigDecimal.valueOf(completed * 100.0 / items.size())
                        .setScale(1, RoundingMode.HALF_UP).doubleValue());
        return analysis;
    }

    private static String overdueAgeKey(WorkItemOverviewItem item) {
        long days = item.getOverdueDays() == null ? 0 : item.getOverdueDays();
        if (days <= 7) return "D1_7";
        if (days <= 30) return "D8_30";
        if (days <= 90) return "D31_90";
        return "D90_PLUS";
    }

    private static String overdueAgeLabel(String key) {
        return switch (key) {
            case "D1_7" -> "逾期 1-7 天";
            case "D8_30" -> "逾期 8-30 天";
            case "D31_90" -> "逾期 31-90 天";
            default -> "逾期 90 天以上";
        };
    }

    private static List<WorkItemDistributionItem> projectDistribution(
            List<? extends WorkItemOverviewItem> items) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (WorkItemOverviewItem item : items) {
            if (item.getProjectIds() == null || item.getProjectIds().isEmpty()) {
                counters.computeIfAbsent("unmapped", key -> new Counter("未映射项目")).count++;
                continue;
            }
            for (int index = 0; index < item.getProjectIds().size(); index++) {
                String key = String.valueOf(item.getProjectIds().get(index));
                String label = item.getProjectNames() != null && index < item.getProjectNames().size()
                        ? item.getProjectNames().get(index) : defaultValue(item.getProjectName(), "项目 " + key);
                counters.computeIfAbsent(key, ignored -> new Counter(label)).count++;
            }
        }
        return toRows(counters, items.size());
    }

    private static List<WorkItemDistributionItem> distribution(
            List<? extends WorkItemOverviewItem> items,
            Function<WorkItemOverviewItem, String> keyGetter,
            Function<String, String> labelGetter) {
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (WorkItemOverviewItem item : items) {
            String key = keyGetter.apply(item);
            counters.computeIfAbsent(key, ignored -> new Counter(labelGetter.apply(key))).count++;
        }
        return toRows(counters, items.size());
    }

    private static List<WorkItemDistributionItem> toRows(Map<String, Counter> counters, int total) {
        return counters.entrySet().stream()
                .map(entry -> new WorkItemDistributionItem(
                        entry.getKey(), entry.getValue().label, entry.getValue().count,
                        total == 0 ? 0 : BigDecimal.valueOf(entry.getValue().count * 100.0 / total)
                                .setScale(1, RoundingMode.HALF_UP).doubleValue()))
                .sorted(java.util.Comparator.comparingLong(WorkItemDistributionItem::getCount).reversed()
                        .thenComparing(WorkItemDistributionItem::getLabel))
                .toList();
    }

    private static String ownerLabel(List<? extends WorkItemOverviewItem> items, String key) {
        if (key.startsWith("unassigned:")) {
            return key.substring("unassigned:".length());
        }
        return items.stream().filter(item -> String.valueOf(item.getAssigneeId()).equals(key))
                .map(WorkItemOverviewItem::getAssigneeName).filter(StringUtils::hasText)
                .findFirst().orElse("用户 " + key);
    }

    private static BigDecimal sumHours(List<? extends WorkItemOverviewItem> items,
                                       Function<WorkItemOverviewItem, BigDecimal> getter) {
        return items.stream().map(getter).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String defaultValue(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static final class Counter {
        private final String label;
        private long count;

        private Counter(String label) {
            this.label = label;
        }
    }
}
