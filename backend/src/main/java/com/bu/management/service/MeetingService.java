package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.ConvertMeetingTodoInput;
import com.bu.management.dto.CreateIssueDTO;
import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.dto.MeetingSegmentInput;
import com.bu.management.dto.MeetingSpeakerInput;
import com.bu.management.dto.MeetingTodoUpdateInput;
import com.bu.management.entity.Issue;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingSpeaker;
import com.bu.management.entity.MeetingTodo;
import com.bu.management.entity.Task;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.service.MeetingAudioStorage.StoredAudio;
import com.bu.management.service.MeetingTranscriptCodec.Segment;
import com.bu.management.vo.MeetingDetailView;
import com.bu.management.vo.MeetingListItem;
import com.bu.management.vo.MeetingSpeakerView;
import com.bu.management.vo.MeetingStatusView;
import com.bu.management.vo.MeetingSummaryView;
import com.bu.management.vo.MeetingTodoView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 会议读写：列表/详情、人工校正、说话人命名、待办编辑与转化、确认、删除。
 * 转写与总结的调度见 MeetingPipelineService。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    /** 转化描述里的证据摘录上限（对齐邮件模块 1500 语义） */
    private static final int EVIDENCE_CAP = 1500;
    private static final int TITLE_CAP = 200;
    private static final Iterable<JsonNode> EMPTY = List.of();

    private final MeetingMapper meetingMapper;
    private final MeetingSpeakerMapper speakerMapper;
    private final MeetingTodoMapper todoMapper;
    private final MeetingTranscriptCodec codec;
    private final MeetingAudioStorage audioStorage;
    private final TaskService taskService;
    private final IssueService issueService;
    private final ObjectMapper objectMapper;

    // ==================== 查询 ====================

    public Page<MeetingListItem> list(Long userId, int page, int size, String status) {
        LambdaQueryWrapper<Meeting> wrapper = new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getOwnerUserId, userId)
                .orderByDesc(Meeting::getUpdatedAt);
        if (StringUtils.hasText(status)) {
            wrapper.eq(Meeting::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        Page<Meeting> stored = meetingMapper.selectPage(new Page<>(page, size), wrapper);
        Page<MeetingListItem> view = new Page<>(stored.getCurrent(), stored.getSize(), stored.getTotal());
        view.setRecords(stored.getRecords().stream().map(MeetingService::toListItem).toList());
        return view;
    }

    public MeetingDetailView detail(Long userId, Long id) {
        Meeting meeting = requireOwned(userId, id);
        List<Segment> segments = segmentsOf(meeting);
        List<MeetingSpeaker> speakers = speakersOf(id);
        return new MeetingDetailView(
                meeting.getId(), meeting.getTitle(), meeting.getMeetingDate(), meeting.getProjectId(),
                meeting.getDurationSeconds(), meeting.getStatus(), meeting.getGenerationModel(),
                meeting.getGenerationError(), segments,
                speakers.stream().map(MeetingService::toSpeakerView).toList(),
                summaryView(meeting, segments, speakerNames(speakers)),
                todosOf(id).stream().map(MeetingService::toTodoView).toList());
    }

    public MeetingStatusView status(Long userId, Long id) {
        Meeting meeting = requireOwned(userId, id);
        return new MeetingStatusView(meeting.getStatus(), meeting.getGenerationError());
    }

    @Transactional
    public MeetingListItem create(Long userId, String title, LocalDate meetingDate, Long projectId,
            StoredAudio audio) {
        Meeting meeting = new Meeting();
        meeting.setOwnerUserId(userId);
        meeting.setTitle(StringUtils.hasText(title) ? title.trim() : "未命名会议");
        meeting.setMeetingDate(meetingDate);
        meeting.setProjectId(projectId);
        meeting.setAudioFilePath(audio.relativePath());
        meeting.setAudioSizeBytes(audio.sizeBytes());
        meeting.setAudioSha256(audio.sha256());
        meeting.setStatus("UPLOADED");
        LocalDateTime now = LocalDateTime.now();
        meeting.setCreatedAt(now);
        meeting.setUpdatedAt(now);
        meetingMapper.insert(meeting);
        return toListItem(meeting);
    }

    // ==================== 编辑 ====================

    /** 保存人工校正稿：时间戳继承原始转写，edited 与原文比对置位；返回归一化后的段。 */
    @Transactional
    public List<Segment> updateTranscript(Long userId, Long id, List<MeetingSegmentInput> inputs) {
        Meeting meeting = requireOwned(userId, id);
        if (inputs == null || inputs.isEmpty()) {
            throw new RuntimeException("转写内容为空");
        }
        Map<Integer, Segment> origin = new HashMap<>();
        for (Segment segment : codec.parse(meeting.getTranscriptJson())) {
            origin.put(segment.seq(), segment);
        }
        Set<String> labels = new LinkedHashSet<>();
        speakersOf(id).forEach(speaker -> labels.add(speaker.getSpeakerLabel()));

        List<Segment> segments = new ArrayList<>();
        for (MeetingSegmentInput input : inputs) {
            if (input.seq() == null) {
                throw new RuntimeException("转写段缺少 seq");
            }
            Segment base = origin.get(input.seq());
            String text = input.text() == null ? "" : input.text();
            String speaker = StringUtils.hasText(input.speaker())
                    ? input.speaker().trim()
                    : (base == null ? null : base.speaker());
            segments.add(new Segment(input.seq(),
                    base == null ? 0L : base.startMs(),
                    base == null ? 0L : base.endMs(),
                    speaker, text,
                    base == null || !text.equals(base.text())));
            if (StringUtils.hasText(speaker)) {
                labels.add(speaker);
            }
        }
        meeting.setCorrectedTranscriptJson(codec.write(segments));
        meeting.setUpdatedAt(LocalDateTime.now());
        meetingMapper.updateById(meeting);
        upsertSpeakerLabels(id, labels);
        return segments;
    }

    @Transactional
    public List<MeetingSpeakerView> updateSpeakers(Long userId, Long id, List<MeetingSpeakerInput> inputs) {
        requireOwned(userId, id);
        List<MeetingSpeakerInput> effective = inputs == null ? List.of() : inputs;
        LocalDateTime now = LocalDateTime.now();
        for (MeetingSpeakerInput input : effective) {
            if (!StringUtils.hasText(input.speakerLabel())) {
                continue;
            }
            String label = input.speakerLabel().trim();
            MeetingSpeaker speaker = speakerMapper.selectOne(new LambdaQueryWrapper<MeetingSpeaker>()
                    .eq(MeetingSpeaker::getMeetingId, id)
                    .eq(MeetingSpeaker::getSpeakerLabel, label));
            if (speaker == null) {
                speaker = new MeetingSpeaker();
                speaker.setMeetingId(id);
                speaker.setSpeakerLabel(label);
                speaker.setCreatedAt(now);
            }
            speaker.setDisplayName(StringUtils.hasText(input.displayName()) ? input.displayName().trim() : null);
            speaker.setMappedUserId(input.mappedUserId());
            speaker.setUpdatedAt(now);
            if (speaker.getId() == null) {
                speakerMapper.insert(speaker);
            } else {
                speakerMapper.updateById(speaker);
            }
        }
        return speakersOf(id).stream().map(MeetingService::toSpeakerView).toList();
    }

    /** 仅 DRAFT 可编辑/忽略。 */
    @Transactional
    public MeetingTodoView updateTodo(Long userId, Long id, Long todoId, MeetingTodoUpdateInput input) {
        requireOwned(userId, id);
        MeetingTodo todo = requireTodo(id, todoId);
        requireDraft(todo);
        if (Boolean.TRUE.equals(input.dismiss())) {
            todo.setStatus("DISMISSED");
        } else {
            if (StringUtils.hasText(input.title())) {
                todo.setTitle(limit(input.title().trim(), TITLE_CAP));
            }
            if (input.description() != null) {
                todo.setDescription(input.description());
            }
            if (input.dueText() != null) {
                todo.setDueText(StringUtils.hasText(input.dueText()) ? input.dueText().trim() : null);
            }
            if (input.dueDate() != null) {
                todo.setDueDate(input.dueDate());
            }
        }
        todo.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(todo);
        return toTodoView(todo);
    }

    /** 转化待办为系统任务/事项：status!=DRAFT 视为已处理（幂等闸门）；actor 一律当前登录人。 */
    @Transactional
    public MeetingTodoView convertTodo(Long userId, Long id, Long todoId, ConvertMeetingTodoInput input) {
        Meeting meeting = requireOwned(userId, id);
        MeetingTodo todo = requireTodo(id, todoId);
        requireDraft(todo);
        String actionType = StringUtils.hasText(input.actionType())
                ? input.actionType().trim().toUpperCase(Locale.ROOT) : "";
        String description = evidence(meeting, todo);
        if ("TASK".equals(actionType)) {
            CreateTaskDTO dto = new CreateTaskDTO();
            dto.setRequirementId(input.requirementId());
            dto.setTitle(limit(todo.getTitle(), TITLE_CAP));
            dto.setDescription(description);
            dto.setAssigneeId(input.assigneeId() != null ? input.assigneeId() : userId);
            dto.setTaskType(StringUtils.hasText(input.taskType()) ? input.taskType().trim() : "开发任务");
            Task created = taskService.createTask(dto, userId);
            todo.setActionType("TASK");
            todo.setTargetId(created.getId());
            todo.setTargetTitle(created.getTitle());
        } else if ("ISSUE".equals(actionType)) {
            CreateIssueDTO dto = new CreateIssueDTO();
            dto.setTitle(limit(todo.getTitle(), TITLE_CAP));
            dto.setDescription(description);
            dto.setIssueType("问题");
            dto.setSeverity(StringUtils.hasText(input.severity()) ? input.severity().trim() : "中");
            dto.setAssigneeId(input.assigneeId() != null ? input.assigneeId() : userId);
            Issue created = issueService.createIssue(dto, userId);
            todo.setActionType("ISSUE");
            todo.setTargetId(created.getId());
            todo.setTargetTitle(created.getTitle());
        } else {
            throw new RuntimeException("转化类型不支持：" + actionType);
        }
        todo.setStatus("CREATED");
        todo.setUpdatedAt(LocalDateTime.now());
        todoMapper.updateById(todo);
        return toTodoView(todo);
    }

    @Transactional
    public MeetingStatusView confirm(Long userId, Long id) {
        Meeting meeting = requireOwned(userId, id);
        if (!"DRAFT".equals(meeting.getStatus())) {
            throw new RuntimeException("仅草稿状态的纪要可确认");
        }
        LocalDateTime now = LocalDateTime.now();
        meeting.setStatus("CONFIRMED");
        meeting.setConfirmedBy(userId);
        meeting.setConfirmedAt(now);
        meeting.setUpdatedAt(now);
        meetingMapper.updateById(meeting);
        return new MeetingStatusView(meeting.getStatus(), meeting.getGenerationError());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Meeting meeting = requireOwned(userId, id);
        todoMapper.delete(new LambdaQueryWrapper<MeetingTodo>().eq(MeetingTodo::getMeetingId, id));
        speakerMapper.delete(new LambdaQueryWrapper<MeetingSpeaker>().eq(MeetingSpeaker::getMeetingId, id));
        meetingMapper.deleteById(id);
        audioStorage.delete(meeting.getAudioFilePath());
    }

    /** 详情与流水线共用的归属校验：不存在或非本人 → 404。 */
    public Meeting requireOwned(Long userId, Long id) {
        Meeting meeting = meetingMapper.selectById(id);
        if (meeting == null || !userId.equals(meeting.getOwnerUserId())) {
            throw new ResourceNotFoundException("会议不存在");
        }
        return meeting;
    }

    /** corrected 优先，否则原始转写。 */
    public List<Segment> segmentsOf(Meeting meeting) {
        String json = StringUtils.hasText(meeting.getCorrectedTranscriptJson())
                ? meeting.getCorrectedTranscriptJson() : meeting.getTranscriptJson();
        return codec.parse(json);
    }

    // ==================== 内部 ====================

    private List<MeetingSpeaker> speakersOf(Long meetingId) {
        return speakerMapper.selectList(new LambdaQueryWrapper<MeetingSpeaker>()
                .eq(MeetingSpeaker::getMeetingId, meetingId)
                .orderByAsc(MeetingSpeaker::getSpeakerLabel));
    }

    private List<MeetingTodo> todosOf(Long meetingId) {
        return todoMapper.selectList(new LambdaQueryWrapper<MeetingTodo>()
                .eq(MeetingTodo::getMeetingId, meetingId)
                .orderByAsc(MeetingTodo::getId));
    }

    private void upsertSpeakerLabels(Long meetingId, Set<String> labels) {
        Map<String, MeetingSpeaker> existing = new HashMap<>();
        for (MeetingSpeaker speaker : speakersOf(meetingId)) {
            existing.put(speaker.getSpeakerLabel(), speaker);
        }
        LocalDateTime now = LocalDateTime.now();
        for (String label : labels) {
            if (existing.containsKey(label)) {
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

    private MeetingTodo requireTodo(Long meetingId, Long todoId) {
        MeetingTodo todo = todoMapper.selectById(todoId);
        if (todo == null || !meetingId.equals(todo.getMeetingId())) {
            throw new ResourceNotFoundException("待办不存在");
        }
        return todo;
    }

    private void requireDraft(MeetingTodo todo) {
        if (!"DRAFT".equals(todo.getStatus())) {
            throw new RuntimeException("该待办已处理");
        }
    }

    private Map<String, String> speakerNames(List<MeetingSpeaker> speakers) {
        Map<String, String> names = new HashMap<>();
        for (MeetingSpeaker speaker : speakers) {
            if (StringUtils.hasText(speaker.getDisplayName())) {
                names.put(speaker.getSpeakerLabel(), speaker.getDisplayName());
            }
        }
        return names;
    }

    /** 来自会议「title」（#id）\n时间：mm:ss–mm:ss\n说话人：…\n\n---- 原文摘录 ----\n…（cap 1500）。 */
    private String evidence(Meeting meeting, MeetingTodo todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("来自会议「").append(meeting.getTitle()).append("」（#").append(meeting.getId()).append("）");
        if (todo.getSourceStartMs() != null) {
            long start = todo.getSourceStartMs();
            long end = todo.getSourceEndMs() == null ? start : todo.getSourceEndMs();
            sb.append("\n时间：").append(MeetingTranscriptCodec.formatTime(start))
                    .append("–").append(MeetingTranscriptCodec.formatTime(end));
        }
        String speaker = speakerOf(meeting, todo);
        if (speaker != null) {
            sb.append("\n说话人：").append(speaker);
        }
        sb.append("\n\n---- 原文摘录 ----\n")
                .append(todo.getSourceExcerpt() == null ? "" : todo.getSourceExcerpt());
        String text = sb.toString();
        return text.length() > EVIDENCE_CAP ? text.substring(0, EVIDENCE_CAP) + "…" : text;
    }

    private String speakerOf(Meeting meeting, MeetingTodo todo) {
        if (todo.getSourceSegmentSeq() == null) {
            return null;
        }
        String label = null;
        for (Segment segment : segmentsOf(meeting)) {
            if (segment.seq() == todo.getSourceSegmentSeq()) {
                label = segment.speaker();
                break;
            }
        }
        if (label == null) {
            return null;
        }
        String displayName = speakerNames(speakersOf(meeting.getId())).get(label);
        return StringUtils.hasText(displayName) ? displayName : label;
    }

    private MeetingSummaryView summaryView(Meeting meeting, List<Segment> segments, Map<String, String> names) {
        if (!StringUtils.hasText(meeting.getSummaryJson())) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(meeting.getSummaryJson());
        } catch (Exception e) {
            throw new IllegalStateException("摘要 JSON 解析失败", e);
        }
        Function<String, String> nameLookup = names::get;
        List<MeetingSummaryView.Section> sections = new ArrayList<>();
        for (JsonNode node : array(root.path("sections"))) {
            sections.add(new MeetingSummaryView.Section(text(node, "title"), text(node, "content"),
                    nullableLong(node, "startMs"), nullableLong(node, "endMs"),
                    intList(node.path("segmentRefs"))));
        }
        List<MeetingSummaryView.SpeakerPoint> speakerPoints = new ArrayList<>();
        for (JsonNode node : array(root.path("speakerPoints"))) {
            speakerPoints.add(new MeetingSummaryView.SpeakerPoint(text(node, "speaker"),
                    textList(node.path("points")), intList(node.path("segmentRefs"))));
        }
        List<MeetingSummaryView.Item> decisions = new ArrayList<>();
        for (JsonNode node : array(root.path("decisions"))) {
            List<Integer> refs = intList(node.path("segmentRefs"));
            decisions.add(new MeetingSummaryView.Item(text(node, "content"),
                    codec.renderExcerpt(segments, refs, nameLookup), startMsOf(segments, refs), refs));
        }
        List<MeetingSummaryView.RiskItem> risks = new ArrayList<>();
        for (JsonNode node : array(root.path("risks"))) {
            List<Integer> refs = intList(node.path("segmentRefs"));
            risks.add(new MeetingSummaryView.RiskItem(text(node, "content"), text(node, "severity"),
                    codec.renderExcerpt(segments, refs, nameLookup), startMsOf(segments, refs), refs));
        }
        return new MeetingSummaryView(text(root, "summary"), textList(root.path("keywords")),
                sections, speakerPoints, decisions, risks);
    }

    private static Long startMsOf(List<Segment> segments, List<Integer> refs) {
        Map<Integer, Segment> bySeq = new HashMap<>();
        for (Segment segment : segments) {
            bySeq.put(segment.seq(), segment);
        }
        for (Integer ref : refs) {
            Segment segment = bySeq.get(ref);
            if (segment != null) {
                return segment.startMs();
            }
        }
        return null;
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : EMPTY;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array(node)) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private static List<Integer> intList(JsonNode node) {
        List<Integer> result = new ArrayList<>();
        for (JsonNode item : array(node)) {
            if (item.isNumber() || item.isTextual()) {
                result.add(item.asInt());
            }
        }
        return result;
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static String limit(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    private static MeetingListItem toListItem(Meeting meeting) {
        return new MeetingListItem(meeting.getId(), meeting.getTitle(), meeting.getMeetingDate(),
                meeting.getDurationSeconds(), meeting.getStatus(), meeting.getGenerationError());
    }

    private static MeetingSpeakerView toSpeakerView(MeetingSpeaker speaker) {
        return new MeetingSpeakerView(speaker.getSpeakerLabel(), speaker.getDisplayName(),
                speaker.getMappedUserId());
    }

    private static MeetingTodoView toTodoView(MeetingTodo todo) {
        return new MeetingTodoView(todo.getId(), todo.getTitle(), todo.getDescription(),
                todo.getAssigneeHint(), todo.getDueText(), todo.getDueDate(), todo.getSourceSegmentSeq(),
                todo.getSourceStartMs(), todo.getSourceEndMs(), todo.getSourceExcerpt(), todo.getStatus(),
                todo.getActionType(), todo.getTargetId(), todo.getTargetTitle());
    }
}
