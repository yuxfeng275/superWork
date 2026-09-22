package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingTodo;
import com.bu.management.integration.MeetingSummaryClient;
import com.bu.management.integration.MeetingTranscriptionClient;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.service.MeetingConfigService.MeetingRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeetingPipelineService 测试")
class MeetingPipelineServiceTest {

    @Mock
    private MeetingMapper meetingMapper;
    @Mock
    private MeetingSpeakerMapper speakerMapper;
    @Mock
    private MeetingTodoMapper todoMapper;
    @Mock
    private MeetingAudioStorage audioStorage;
    @Mock
    private MeetingTranscriptionClient transcriptionClient;
    @Mock
    private MeetingSummaryClient summaryClient;
    @Mock
    private MeetingCorrectionService correctionService;
    @Mock
    private MeetingConfigService configService;

    private ObjectMapper objectMapper;
    private MeetingPipelineService pipeline;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        pipeline = new MeetingPipelineService(meetingMapper, speakerMapper, todoMapper, audioStorage,
                transcriptionClient, summaryClient, correctionService, configService,
                new MeetingTranscriptCodec(objectMapper), objectMapper);
        ReflectionTestUtils.setField(pipeline, "taskExecutor", (Executor) Runnable::run);
    }

    @Test
    @DisplayName("ASR 调用失败 → FAILED 且错误信息落库")
    void process_asrFailure_marksFailed() {
        Meeting meeting = meeting("UPLOADED");
        when(meetingMapper.selectById(1L)).thenReturn(meeting);
        when(configService.load()).thenReturn(
                new MeetingRuntimeConfig(false, "http://localhost:8790", "", 60, "", 100));
        when(audioStorage.pathOf("a.m4a")).thenReturn(Path.of("/tmp/a.m4a"));
        when(transcriptionClient.transcribe(any(), any(), any()))
                .thenThrow(new IllegalStateException("会议转写未配置"));

        pipeline.process(1L);

        assertThat(meeting.getStatus()).isEqualTo("FAILED");
        assertThat(meeting.getGenerationError()).isEqualTo("会议转写未配置");
        verify(meetingMapper, atLeastOnce()).updateById(meeting);
    }

    @Test
    @DisplayName("模型缺增强字段 → 落库为空数组且不 FAILED")
    void summarize_missingEnhancedFields_storesEmptyArrays() throws Exception {
        Meeting meeting = meeting("DRAFT");
        stubSummary(meeting, """
                {"summary":"概览","decisions":[],"risks":[],"todos":[]}""");

        pipeline.summarize(1L);

        assertThat(meeting.getStatus()).isEqualTo("DRAFT");
        assertThat(meeting.getGenerationError()).isNull();
        JsonNode stored = objectMapper.readTree(meeting.getSummaryJson());
        assertThat(stored.path("keywords")).isEmpty();
        assertThat(stored.path("sections")).isEmpty();
        assertThat(stored.path("speakerPoints")).isEmpty();
        assertThat(meeting.getGenerationModel()).isEqualTo("http://glm/glm-4");
    }

    @Test
    @DisplayName("章节越界 ref 被过滤、章节保留；未知说话人要点丢弃")
    void summarize_filtersEnhancedFields() throws Exception {
        Meeting meeting = meeting("DRAFT");
        stubSummary(meeting, """
                {"summary":"概览","sections":[{"title":"开场","content":"对齐目标","startMs":0,"endMs":1000,\
                "segmentRefs":[1,99,-3]}],"speakerPoints":[{"speaker":"SPEAKER_09","points":["编造"],"segmentRefs":[1]},\
                {"speaker":"SPEAKER_00","points":["真实"],"segmentRefs":[1]}],"decisions":[],"risks":[],"todos":[]}""");

        pipeline.summarize(1L);

        assertThat(meeting.getStatus()).isEqualTo("DRAFT");
        JsonNode stored = objectMapper.readTree(meeting.getSummaryJson());
        assertThat(stored.path("sections")).singleElement().satisfies(section -> {
            assertThat(section.path("title").asText()).isEqualTo("开场");
            assertThat(section.path("segmentRefs")).singleElement()
                    .satisfies(ref -> assertThat(ref.asInt()).isEqualTo(1));
        });
        assertThat(stored.path("speakerPoints")).singleElement().satisfies(point ->
                assertThat(point.path("speaker").asText()).isEqualTo("SPEAKER_00"));
    }

    @Test
    @DisplayName("核心字段 segmentRef 越界 → FAILED")
    void summarize_outOfRangeDecisionRef_marksFailed() throws Exception {
        Meeting meeting = meeting("DRAFT");
        stubSummary(meeting, """
                {"summary":"概览","decisions":[{"content":"决议","segmentRefs":[99]}],"risks":[],"todos":[]}""");

        pipeline.summarize(1L);

        assertThat(meeting.getStatus()).isEqualTo("FAILED");
        assertThat(meeting.getGenerationError()).contains("越界");
    }

    @Test
    @DisplayName("重跑总结只删除 DRAFT 草稿并按新结果重建")
    void summarize_replacesOnlyDraftTodos() throws Exception {
        Meeting meeting = meeting("DRAFT");
        stubSummary(meeting, """
                {"summary":"概览","decisions":[],"risks":[],"todos":[\
                {"title":"输出方案","dueText":"下周五","dueDate":"2026-09-25","segmentRef":1},\
                {"title":"补充数据","segmentRef":1}]}""");
        when(todoMapper.delete(any())).thenReturn(1);

        pipeline.summarize(1L);

        verify(todoMapper).delete(any());
        ArgumentCaptor<MeetingTodo> captor = ArgumentCaptor.forClass(MeetingTodo.class);
        verify(todoMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(todo -> {
                    assertThat(todo.getStatus()).isEqualTo("DRAFT");
                    assertThat(todo.getMeetingId()).isEqualTo(1L);
                })
                .anySatisfy(todo -> {
                    assertThat(todo.getTitle()).isEqualTo("输出方案");
                    assertThat(todo.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 25));
                    assertThat(todo.getSourceExcerpt()).contains("00:00 SPEAKER_00");
                });
    }

    @Test
    @DisplayName("纠偏稿写入 corrected_transcript_json，总结输入用纠偏后的文本")
    void process_appliesCorrectionBeforeSummary() throws Exception {
        Meeting meeting = meeting("UPLOADED");
        when(meetingMapper.selectById(1L)).thenReturn(meeting);
        when(configService.load()).thenReturn(
                new MeetingRuntimeConfig(false, "http://localhost:8790", "", 60, "CDP", 100));
        when(audioStorage.pathOf("a.m4a")).thenReturn(Path.of("/tmp/a.m4a"));
        when(transcriptionClient.transcribe(any(), any(), any())).thenReturn(
                new MeetingTranscriptionClient.TranscriptionResult(5000, "stub",
                        List.of(new MeetingTranscriptionClient.Segment(1, 0, 5000,
                                "SPEAKER_00", "先对齐 c d p 项目"))));
        when(correctionService.correct(anyList(), any())).thenReturn(List.of(
                new MeetingTranscriptCodec.Segment(1, 0, 5000, "SPEAKER_00", "先对齐 CDP 项目", true)));
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(
                "{\"summary\":\"概览\",\"decisions\":[],\"risks\":[],\"todos\":[]}"));
        when(speakerMapper.selectList(any())).thenReturn(List.of());
        when(todoMapper.delete(any())).thenReturn(1);
        lenient().when(summaryClient.describeModel()).thenReturn("http://glm/glm-4");
        lenient().when(meetingMapper.updateById(meeting)).thenReturn(1);

        pipeline.process(1L);

        assertThat(meeting.getStatus()).isEqualTo("DRAFT");
        assertThat(meeting.getCorrectedTranscriptJson()).contains("CDP");
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(summaryClient).chatJson(any(), inputCaptor.capture());
        assertThat(inputCaptor.getValue()).contains("CDP").doesNotContain("c d p");
    }

    private void stubSummary(Meeting meeting, String modelJson) throws Exception {
        when(meetingMapper.selectById(1L)).thenReturn(meeting);
        when(speakerMapper.selectList(any())).thenReturn(List.of());
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(modelJson));
        // 失败路径（校验抛错）不会走到 describeModel，放宽该 stub
        lenient().when(summaryClient.describeModel()).thenReturn("http://glm/glm-4");
        lenient().when(meetingMapper.updateById(meeting)).thenReturn(1);
    }

    private static Meeting meeting(String status) {
        Meeting meeting = new Meeting();
        meeting.setId(1L);
        meeting.setOwnerUserId(7L);
        meeting.setTitle("周会");
        meeting.setMeetingDate(LocalDate.of(2026, 9, 16));
        meeting.setStatus(status);
        meeting.setAudioFilePath("a.m4a");
        meeting.setTranscriptJson("{\"segments\":[{\"seq\":1,\"startMs\":0,\"endMs\":1000,"
                + "\"speaker\":\"SPEAKER_00\",\"text\":\"小李下周五给方案\",\"edited\":false}]}");
        return meeting;
    }
}
