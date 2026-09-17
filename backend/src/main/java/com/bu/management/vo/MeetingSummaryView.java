package com.bu.management.vo;

import java.util.List;

/**
 * 摘要视图（钉钉闪记式）：核心字段 + 增强字段。
 * decisions/risks 的 excerpt 在读取时从转写段服务端渲染，startMs 为首个有效 ref 的时间。
 */
public record MeetingSummaryView(
        String summary,
        List<String> keywords,
        List<Section> sections,
        List<SpeakerPoint> speakerPoints,
        List<Item> decisions,
        List<RiskItem> risks) {

    public record Section(String title, String content, Long startMs, Long endMs, List<Integer> segmentRefs) {
    }

    public record SpeakerPoint(String speaker, List<String> points, List<Integer> segmentRefs) {
    }

    public record Item(String content, String excerpt, Long startMs, List<Integer> segmentRefs) {
    }

    public record RiskItem(String content, String severity, String excerpt, Long startMs,
            List<Integer> segmentRefs) {
    }
}
