package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingSpeaker;
import com.bu.management.entity.MeetingTodo;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.integration.MeetingSummaryClient;
import com.bu.management.integration.MeetingTranscriptionClient;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.service.MeetingConfigService.MeetingRuntimeConfig;
import com.bu.management.service.MeetingTranscriptCodec.Segment;
import com.bu.management.vo.MeetingStatusView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 会议流水线：转写（worker）→ LLM 总结（钉钉闪记式图文并茂）→ 待办草稿。
 * 全部异常吞掉转 FAILED 并落 generation_error；重跑总结只替换 DRAFT 草稿。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingPipelineService {

    private static final int TRANSCRIPT_CAP = 120_000;
    private static final int SUMMARY_SECTIONS_CAP = 20;
    private static final int KEYWORDS_CAP = 10;
    private static final int KEYWORD_LENGTH = 20;
    private static final int SPEAKER_POINTS_CAP = 5;
    private static final int SPEAKER_POINT_LENGTH = 100;
    private static final int TODO_TITLE_CAP = 200;
    private static final int ERROR_CAP = 500;

    private static final String SYSTEM_PROMPT = """
            你是企业会议纪要助手。输入是带 [seq][mm:ss][说话人] 的会议转写全文；只依据转写内容输出，不得编造。
            输出严格 JSON（不要输出 JSON 之外的任何文字），结构如下：
            {"summary":"3-5 句概览","keywords":["CDP","MA"],\
            "sections":[{"title":"开场与目标对齐","content":"1-3 句","startMs":0,"endMs":832000,"segmentRefs":[1,2,3]}],\
            "speakerPoints":[{"speaker":"SPEAKER_00","points":["要点"],"segmentRefs":[1,5]}],\
            "decisions":[{"content":"决议","segmentRefs":[3,4]}],\
            "risks":[{"content":"风险","severity":"高","segmentRefs":[7]}],\
            "todos":[{"title":"动作","description":"说明","assigneeHint":"小李","dueText":"下周五","dueDate":"2026-09-25","segmentRef":12}]}
            规则：
            概览 summary 概括会议主题、结论与后续动作。
            章节速览 sections：3-8 个、按时间顺序；title 不超过 20 字；content 1-3 句；startMs/endMs 取该章首末 segment 的 startMs/endMs；segmentRefs 列出该章包含的全部 seq。
            keywords：3-8 个本次会议涉及的产品/项目/业务术语（如 CDP、MA、BI），4 字以内优先。
            speakerPoints：每个实际发言的说话人输出 1-3 条要点，每条不超过 50 字，并附 segmentRefs 证据；说话人只能用转写里给出的标签。
            decisions/risks 每条都要带 segmentRefs 证据；severity 只取 高/中/低。
            待办 todos：title 必须是可交付动作（不要"持续跟进"这类空话）；assigneeHint 只取语句中指名的人（≠发言人），没有就留空；dueText 保留原话；dueDate 仅在能由会议日期推算时输出（ISO 日期）；拿不准的承诺不要生成待办。
            """;

    private final MeetingMapper meetingMapper;
    private final MeetingSpeakerMapper speakerMapper;
    private final MeetingTodoMapper todoMapper;
    private final MeetingAudioStorage audioStorage;
    private final MeetingTranscriptionClient transcriptionClient;
    private final MeetingSummaryClient summaryClient;
    private final MeetingConfigService configService;
    private final MeetingTranscriptCodec codec;
    private final ObjectMapper objectMapper;

    @Resource(name = "meetingTaskExecutor")
    private Executor taskExecutor;

    // ==================== 调度 ====================

    public void startProcessing(Long meetingId) {
        taskExecutor.execute(() -> process(meetingId));
    }

    /** FAILED → 重新转写。 */
    public MeetingStatusView reprocess(Long meetingId) {
        Meeting meeting = require(meetingId);
        if (!"FAILED".equals(meeting.getStatus())) {
            throw new RuntimeException("仅失败的会议可重新转写");
        }
        updateStatus(meeting, "TRANSCRIBING", null);
        startProcessing(meetingId);
        return new MeetingStatusView(meeting.getStatus(), null);
    }

    /** DRAFT/CONFIRMED → 重跑总结（异步入队，前端轮询 status）。 */
    public MeetingStatusView summarize(Long meetingId) {
        Meeting meeting = require(meetingId);
        if (!"DRAFT".equals(meeting.getStatus()) && !"CONFIRMED".equals(meeting.getStatus())) {
            throw new RuntimeException("仅草稿或已确认的会议可重新总结");
        }
        updateStatus(meeting, "SUMMARIZING", null);
        taskExecutor.execute(() -> runSummarize(meetingId));
        return new MeetingStatusView(meeting.getStatus(), null);
    }

    // ==================== 流水线 ====================

    /** 转写 + 总结全流程；任何异常 → FAILED（错误对外可见）。 */
    public void process(Long meetingId) {
        try {
            Meeting meeting = require(meetingId);
            updateStatus(meeting, "TRANSCRIBING", null);
            transcribe(meeting);
            updateStatus(meeting, "SUMMARIZING", null);
            summarizeInternal(meetingId);
            updateStatus(require(meetingId), "DRAFT", null);
        } catch (Exception e) {
            fail(meetingId, e);
        }
    }

    private void runSummarize(Long meetingId) {
        try {
            summarizeInternal(meetingId);
            updateStatus(require(meetingId), "DRAFT", null);
        } catch (Exception e) {
            fail(meetingId, e);
        }
    }

    private void transcribe(Meeting meeting) {
        MeetingRuntimeConfig config = configService.load();
        MeetingTranscriptionClient.TranscriptionResult result = transcriptionClient.transcribe(
                audioStorage.pathOf(meeting.getAudioFilePath()),
                meeting.getAudioFilePath(), config.asrHotwords());
        List<Segment> segments = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        for (MeetingTranscriptionClient.Segment source : result.segments()) {
            segments.add(new Segment(source.index(), source.startMs(), source.endMs(),
                    source.speaker(), source.text(), false));
            if (StringUtils.hasText(source.speaker())) {
                labels.add(source.speaker());
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("转写结果为空");
        }
        long durationMs = result.durationMs() > 0 ? result.durationMs()
                : segments.stream().mapToLong(Segment::endMs).max().orElse(0L);
        meeting.setTranscriptJson(codec.write(segments));
        meeting.setDurationSeconds((int) Math.max(0, durationMs / 1000));
        meeting.setUpdatedAt(LocalDateTime.now());
        meetingMapper.updateById(meeting);
        upsertSpeakers(meeting.getId(), labels);
    }

    /** 汇总 + 校验 + 落库（summary_json 与 DRAFT 待办）。 */
    private void summarizeInternal(Long meetingId) {
        Meeting meeting = require(meetingId);
        List<Segment> segments = codec.parse(StringUtils.hasText(meeting.getCorrectedTranscriptJson())
                ? meeting.getCorrectedTranscriptJson() : meeting.getTranscriptJson());
        if (segments.isEmpty()) {
            throw new IllegalStateException("转写为空，无法总结");
        }
        Map<String, String> names = speakerNames(meetingId);
        String input = renderTranscript(segments, names);
        if (input.length() > TRANSCRIPT_CAP) {
            throw new IllegalStateException("录音过长，请分段上传");
        }
        JsonNode raw = summaryClient.chatJson(SYSTEM_PROMPT, input);
        ObjectNode normalized = normalize(raw, segments, meeting, speakerLabels(meetingId, segments));
        meeting.setSummaryJson(normalized.toString());
        meeting.setGenerationModel(summaryClient.describeModel());
        meeting.setGenerationError(null);
        meeting.setUpdatedAt(LocalDateTime.now());
        meetingMapper.updateById(meeting);
        replaceDraftTodos(meeting, normalized.path("todos"), segments);
    }

    // ==================== 校验与归一 ====================

    /**
     * 核心字段（summary/decisions/risks/todos）严格：缺失或 segmentRef 越界 → IllegalStateException。
     * 增强字段（keywords/sections/speakerPoints）宽松：能修则修，不阻断核心纪要。
     */
    private ObjectNode normalize(JsonNode root, List<Segment> segments, Meeting meeting,
            Set<String> speakerLabels) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("模型返回不是 JSON 对象");
        }
        String summary = text(root, "summary");
        if (!StringUtils.hasText(summary)) {
            throw new IllegalStateException("模型返回缺少 summary");
        }
        Set<Integer> validSeqs = new HashSet<>(codec.seqs(segments));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("summary", summary.trim());

        // keywords：去空白去重、≤10 个、每个 ≤20 字
        ArrayNode keywords = result.putArray("keywords");
        Set<String> keywordSeen = new HashSet<>();
        for (JsonNode node : array(root.path("keywords"))) {
            if (!node.isTextual() || !StringUtils.hasText(node.asText())) {
                continue;
            }
            String value = shorten(node.asText().trim(), KEYWORD_LENGTH);
            if (keywordSeen.add(value) && keywords.size() < KEYWORDS_CAP) {
                keywords.add(value);
            }
        }

        // sections：≤20 个；title/content 必须有值，否则丢弃该项；时间非负否则 null；refs 过滤
        ArrayNode sections = result.putArray("sections");
        for (JsonNode node : array(root.path("sections"))) {
            if (sections.size() >= SUMMARY_SECTIONS_CAP) {
                break;
            }
            String title = text(node, "title");
            String content = text(node, "content");
            if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
                continue;
            }
            ObjectNode section = sections.addObject();
            section.put("title", title.trim());
            section.put("content", content.trim());
            putNullableLong(section, "startMs", nonNegative(node, "startMs"));
            putNullableLong(section, "endMs", nonNegative(node, "endMs"));
            putFilteredRefs(section.putArray("segmentRefs"), node.path("segmentRefs"), validSeqs);
        }

        // speakerPoints：丢弃未知说话人；points ≤5 条、每条 ≤100 字；refs 过滤
        ArrayNode speakerPoints = result.putArray("speakerPoints");
        for (JsonNode node : array(root.path("speakerPoints"))) {
            String speaker = text(node, "speaker");
            if (!StringUtils.hasText(speaker) || !speakerLabels.contains(speaker.trim())) {
                continue;
            }
            ObjectNode point = speakerPoints.addObject();
            point.put("speaker", speaker.trim());
            ArrayNode points = point.putArray("points");
            for (JsonNode value : array(node.path("points"))) {
                if (points.size() >= SPEAKER_POINTS_CAP) {
                    break;
                }
                if (value.isTextual() && StringUtils.hasText(value.asText())) {
                    points.add(shorten(value.asText().trim(), SPEAKER_POINT_LENGTH));
                }
            }
            putFilteredRefs(point.putArray("segmentRefs"), node.path("segmentRefs"), validSeqs);
        }

        // decisions：content 必填；refs 越界 → FAILED
        ArrayNode decisions = result.putArray("decisions");
        for (JsonNode node : array(root.path("decisions"))) {
            String content = text(node, "content");
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("决议缺少 content");
            }
            ObjectNode decision = decisions.addObject();
            decision.put("content", content.trim());
            putStrictRefs(decision.putArray("segmentRefs"), node.path("segmentRefs"), validSeqs);
        }

        // risks：content 必填；severity 归一；refs 越界 → FAILED
        ArrayNode risks = result.putArray("risks");
        for (JsonNode node : array(root.path("risks"))) {
            String content = text(node, "content");
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("风险缺少 content");
            }
            ObjectNode risk = risks.addObject();
            risk.put("content", content.trim());
            String severity = text(node, "severity");
            risk.put("severity", StringUtils.hasText(severity) ? severity.trim() : "中");
            putStrictRefs(risk.putArray("segmentRefs"), node.path("segmentRefs"), validSeqs);
        }

        // todos：title 必填；ref 越界 → FAILED；dueDate 仅在 [会议日-1年, 会议日+2年] 内采纳
        ArrayNode todos = result.putArray("todos");
        for (JsonNode node : array(root.path("todos"))) {
            String title = text(node, "title");
            if (!StringUtils.hasText(title)) {
                throw new IllegalStateException("待办缺少 title");
            }
            ObjectNode todo = todos.addObject();
            todo.put("title", shorten(title.trim(), TODO_TITLE_CAP));
            putIfText(todo, "description", text(node, "description"));
            putIfText(todo, "assigneeHint", text(node, "assigneeHint"));
            putIfText(todo, "dueText", text(node, "dueText"));
            LocalDate dueDate = acceptDueDate(text(node, "dueDate"), meeting.getMeetingDate());
            if (dueDate != null) {
                todo.put("dueDate", dueDate.toString());
            }
            Integer ref = node.path("segmentRef").isNumber() ? node.path("segmentRef").asInt() : null;
            if (ref != null) {
                if (!validSeqs.contains(ref)) {
                    throw new IllegalStateException("待办引用转写段越界：" + ref);
                }
                todo.put("segmentRef", ref);
            }
        }
        return result;
    }

    /** 重跑只替换 DRAFT 草稿：CREATED/DISMISSED 保留。 */
    private void replaceDraftTodos(Meeting meeting, JsonNode todosNode, List<Segment> segments) {
        todoMapper.delete(new LambdaQueryWrapper<MeetingTodo>()
                .eq(MeetingTodo::getMeetingId, meeting.getId())
                .eq(MeetingTodo::getStatus, "DRAFT"));
        Map<Integer, Segment> bySeq = new HashMap<>();
        for (Segment segment : segments) {
            bySeq.put(segment.seq(), segment);
        }
        LocalDateTime now = LocalDateTime.now();
        for (JsonNode node : array(todosNode)) {
            MeetingTodo todo = new MeetingTodo();
            todo.setMeetingId(meeting.getId());
            todo.setTitle(shorten(text(node, "title"), TODO_TITLE_CAP));
            todo.setDescription(text(node, "description"));
            todo.setAssigneeHint(text(node, "assigneeHint"));
            todo.setDueText(text(node, "dueText"));
            String dueDate = text(node, "dueDate");
            todo.setDueDate(dueDate == null ? null : LocalDate.parse(dueDate));
            Integer ref = node.path("segmentRef").isNumber() ? node.path("segmentRef").asInt() : null;
            if (ref != null) {
                Segment segment = bySeq.get(ref);
                if (segment != null) {
                    todo.setSourceSegmentSeq(segment.seq());
                    todo.setSourceStartMs(segment.startMs());
                    todo.setSourceEndMs(segment.endMs());
                    todo.setSourceExcerpt(codec.renderExcerpt(segments, List.of(ref), null));
                }
            }
            todo.setStatus("DRAFT");
            todo.setCreatedAt(now);
            todo.setUpdatedAt(now);
            todoMapper.insert(todo);
        }
    }

    // ==================== 内部 ====================

    private Meeting require(Long meetingId) {
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting == null) {
            throw new ResourceNotFoundException("会议不存在");
        }
        return meeting;
    }

    private void updateStatus(Meeting meeting, String status, String error) {
        meeting.setStatus(status);
        meeting.setGenerationError(error);
        meeting.setUpdatedAt(LocalDateTime.now());
        meetingMapper.updateById(meeting);
    }

    private void fail(Long meetingId, Exception e) {
        log.warn("会议处理失败 meetingId={}: {}", meetingId, e.toString());
        Meeting meeting = meetingMapper.selectById(meetingId);
        if (meeting != null) {
            updateStatus(meeting, "FAILED", sanitize(e.getMessage()));
        }
    }

    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "处理失败";
        }
        String trimmed = message.strip();
        return trimmed.length() > ERROR_CAP ? trimmed.substring(0, ERROR_CAP) : trimmed;
    }

    private void upsertSpeakers(Long meetingId, Set<String> labels) {
        Set<String> existing = new HashSet<>();
        speakerMapper.selectList(new LambdaQueryWrapper<MeetingSpeaker>()
                        .eq(MeetingSpeaker::getMeetingId, meetingId))
                .forEach(speaker -> existing.add(speaker.getSpeakerLabel()));
        LocalDateTime now = LocalDateTime.now();
        for (String label : labels) {
            if (existing.contains(label)) {
                continue;
            }
            MeetingSpeaker speaker = new MeetingSpeaker();
            speaker.setMeetingId(meetingId);
            speaker.setSpeakerLabel(label);
            speaker.setCreatedAt(now);
            speaker.setUpdatedAt(now);
            speakerMapper.insert(speaker);
        }
    }

    private Map<String, String> speakerNames(Long meetingId) {
        Map<String, String> names = new HashMap<>();
        speakerMapper.selectList(new LambdaQueryWrapper<MeetingSpeaker>()
                        .eq(MeetingSpeaker::getMeetingId, meetingId))
                .forEach(speaker -> {
                    if (StringUtils.hasText(speaker.getDisplayName())) {
                        names.put(speaker.getSpeakerLabel(), speaker.getDisplayName());
                    }
                });
        return names;
    }

    private Set<String> speakerLabels(Long meetingId, List<Segment> segments) {
        Set<String> labels = new HashSet<>();
        for (Segment segment : segments) {
            if (StringUtils.hasText(segment.speaker())) {
                labels.add(segment.speaker());
            }
        }
        speakerMapper.selectList(new LambdaQueryWrapper<MeetingSpeaker>()
                        .eq(MeetingSpeaker::getMeetingId, meetingId))
                .forEach(speaker -> labels.add(speaker.getSpeakerLabel()));
        return labels;
    }

    private String renderTranscript(List<Segment> segments, Map<String, String> names) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            String name = names.getOrDefault(segment.speaker(), segment.speaker());
            sb.append('[').append(segment.seq()).append("][")
                    .append(MeetingTranscriptCodec.formatTime(segment.startMs())).append("][")
                    .append(name == null ? "-" : name).append(']')
                    .append(segment.text() == null ? "" : segment.text())
                    .append('\n');
        }
        return sb.toString();
    }

    /** dueDate 仅当落在 [meeting_date-1年, meeting_date+2年] 内才采纳。 */
    private static LocalDate acceptDueDate(String value, LocalDate meetingDate) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
        LocalDate anchor = meetingDate == null ? LocalDate.now() : meetingDate;
        return parsed.isBefore(anchor.minusYears(1)) || parsed.isAfter(anchor.plusYears(2)) ? null : parsed;
    }

    private static void putFilteredRefs(ArrayNode target, JsonNode refs, Set<Integer> validSeqs) {
        for (JsonNode ref : array(refs)) {
            if ((ref.isNumber() || ref.isTextual()) && validSeqs.contains(ref.asInt())) {
                target.add(ref.asInt());
            }
        }
    }

    private static void putStrictRefs(ArrayNode target, JsonNode refs, Set<Integer> validSeqs) {
        for (JsonNode ref : array(refs)) {
            if (!ref.isNumber() && !ref.isTextual()) {
                continue;
            }
            if (!validSeqs.contains(ref.asInt())) {
                throw new IllegalStateException("证据引用转写段越界：" + ref.asInt());
            }
            target.add(ref.asInt());
        }
    }

    private static void putNullableLong(ObjectNode target, String field, Long value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    private static void putIfText(ObjectNode target, String field, String value) {
        if (StringUtils.hasText(value)) {
            target.put(field, value.trim());
        }
    }

    private static Long nonNegative(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || value.asLong() < 0) {
            return null;
        }
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : List.<JsonNode>of();
    }
}
