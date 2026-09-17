package com.bu.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 转写 JSON（transcript_json / corrected_transcript_json）编解码与原文摘录渲染。
 */
@Component
@RequiredArgsConstructor
public class MeetingTranscriptCodec {

    /** 摘录上限（与待办 source_excerpt 列一致） */
    public static final int EXCERPT_CAP = 1000;

    private final ObjectMapper objectMapper;

    /** 单个转写段；字段名即前端/存储契约。 */
    public record Segment(int seq, long startMs, long endMs, String speaker, String text, boolean edited) {
    }

    public List<Segment> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode segments = objectMapper.readTree(json).path("segments");
            if (!segments.isArray()) {
                return List.of();
            }
            List<Segment> result = new ArrayList<>();
            for (JsonNode node : segments) {
                result.add(new Segment(
                        node.path("seq").asInt(),
                        node.path("startMs").asLong(),
                        node.path("endMs").asLong(),
                        node.path("speaker").asText(null),
                        node.path("text").asText(""),
                        node.path("edited").asBoolean(false)));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("转写 JSON 解析失败", e);
        }
    }

    public String write(List<Segment> segments) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode array = root.putArray("segments");
            for (Segment segment : segments) {
                ObjectNode node = array.addObject();
                node.put("seq", segment.seq());
                node.put("startMs", segment.startMs());
                node.put("endMs", segment.endMs());
                node.put("speaker", segment.speaker());
                node.put("text", segment.text());
                node.put("edited", segment.edited());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("转写 JSON 序列化失败", e);
        }
    }

    /** 有效 seq 集合，用于 segmentRefs 过滤/越界校验。 */
    public List<Integer> seqs(List<Segment> segments) {
        return segments.stream().map(Segment::seq).toList();
    }

    /**
     * 按 refs 渲染原文摘录（cap {@value #EXCERPT_CAP}）：每行 "mm:ss 说话人：文本"。
     *
     * @param speakerName label → 展示名（可为 null，取原始 label）
     * @return 无有效 ref 时返回 null
     */
    public String renderExcerpt(List<Segment> segments, List<Integer> refs,
            Function<String, String> speakerName) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        Map<Integer, Segment> bySeq = new HashMap<>();
        for (Segment segment : segments) {
            bySeq.put(segment.seq(), segment);
        }
        StringBuilder sb = new StringBuilder();
        for (Integer ref : refs) {
            Segment segment = ref == null ? null : bySeq.get(ref);
            if (segment == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            String name = speakerName == null ? null : speakerName.apply(segment.speaker());
            sb.append(formatTime(segment.startMs())).append(' ')
                    .append(name == null || name.isBlank() ? segment.speaker() : name)
                    .append("：").append(segment.text());
            if (sb.length() >= EXCERPT_CAP) {
                break;
            }
        }
        if (sb.isEmpty()) {
            return null;
        }
        return sb.length() > EXCERPT_CAP ? sb.substring(0, EXCERPT_CAP) + "…" : sb.toString();
    }

    /** 毫秒 → mm:ss（负数按 0 处理）。 */
    public static String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
