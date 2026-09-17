package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.dto.ConvertMeetingTodoInput;
import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingSpeaker;
import com.bu.management.entity.MeetingTodo;
import com.bu.management.entity.Task;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingSpeakerMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.vo.MeetingTodoView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeetingService 测试")
class MeetingServiceTest {

    @Mock
    private MeetingMapper meetingMapper;
    @Mock
    private MeetingSpeakerMapper speakerMapper;
    @Mock
    private MeetingTodoMapper todoMapper;
    @Mock
    private MeetingAudioStorage audioStorage;
    @Mock
    private TaskService taskService;
    @Mock
    private IssueService issueService;

    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        meetingService = new MeetingService(meetingMapper, speakerMapper, todoMapper,
                new MeetingTranscriptCodec(objectMapper), audioStorage, taskService, issueService, objectMapper);
    }

    @Test
    @DisplayName("转任务允许不带 requirementId，并写入证据描述与当前用户为 actor")
    void convertTodo_taskWithoutRequirement() {
        Meeting meeting = meeting(5L, 7L);
        MeetingTodo todo = draftTodo(11L, 5L, 1);
        when(meetingMapper.selectById(5L)).thenReturn(meeting);
        when(todoMapper.selectById(11L)).thenReturn(todo);
        when(speakerMapper.selectList(any())).thenReturn(List.of(speaker("SPEAKER_00", "小李")));
        Task created = new Task();
        created.setId(100L);
        created.setTitle("输出方案");
        when(taskService.createTask(any(), any())).thenReturn(created);

        MeetingTodoView view = meetingService.convertTodo(7L, 5L, 11L,
                new ConvertMeetingTodoInput("TASK", null, null, null, null));

        ArgumentCaptor<CreateTaskDTO> captor = ArgumentCaptor.forClass(CreateTaskDTO.class);
        verify(taskService).createTask(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        CreateTaskDTO dto = captor.getValue();
        assertThat(dto.getRequirementId()).isNull();
        assertThat(dto.getAssigneeId()).isEqualTo(7L);
        assertThat(dto.getTaskType()).isEqualTo("开发任务");
        assertThat(dto.getDescription())
                .contains("来自会议「周会」（#5）")
                .contains("时间：00:00–00:01")
                .contains("说话人：小李")
                .contains("---- 原文摘录 ----");
        assertThat(view.status()).isEqualTo("CREATED");
        assertThat(view.actionType()).isEqualTo("TASK");
        assertThat(view.targetId()).isEqualTo(100L);
        verify(todoMapper).updateById(todo);
    }

    @Test
    @DisplayName("已处理待办二次转化被拒")
    void convertTodo_alreadyCreated_rejected() {
        Meeting meeting = meeting(5L, 7L);
        MeetingTodo todo = draftTodo(11L, 5L, 1);
        todo.setStatus("CREATED");
        when(meetingMapper.selectById(5L)).thenReturn(meeting);
        when(todoMapper.selectById(11L)).thenReturn(todo);

        assertThatThrownBy(() -> meetingService.convertTodo(7L, 5L, 11L,
                new ConvertMeetingTodoInput("TASK", null, null, null, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("该待办已处理");
    }

    @Test
    @DisplayName("非上传人访问会议 → 404")
    void requireOwned_foreignMeeting_notFound() {
        Meeting meeting = meeting(5L, 8L);
        when(meetingMapper.selectById(5L)).thenReturn(meeting);

        assertThatThrownBy(() -> meetingService.requireOwned(7L, 5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("会议不存在");
    }

    private static Meeting meeting(Long id, Long ownerUserId) {
        Meeting meeting = new Meeting();
        meeting.setId(id);
        meeting.setOwnerUserId(ownerUserId);
        meeting.setTitle("周会");
        meeting.setMeetingDate(LocalDate.of(2026, 9, 16));
        meeting.setStatus("DRAFT");
        meeting.setTranscriptJson("{\"segments\":[{\"seq\":1,\"startMs\":0,\"endMs\":1000,"
                + "\"speaker\":\"SPEAKER_00\",\"text\":\"小李下周五给方案\",\"edited\":false}]}");
        return meeting;
    }

    private static MeetingTodo draftTodo(Long id, Long meetingId, int segmentSeq) {
        MeetingTodo todo = new MeetingTodo();
        todo.setId(id);
        todo.setMeetingId(meetingId);
        todo.setTitle("输出方案");
        todo.setStatus("DRAFT");
        todo.setSourceSegmentSeq(segmentSeq);
        todo.setSourceStartMs(0L);
        todo.setSourceEndMs(1000L);
        todo.setSourceExcerpt("00:00 SPEAKER_00：小李下周五给方案");
        return todo;
    }

    private static MeetingSpeaker speaker(String label, String displayName) {
        MeetingSpeaker speaker = new MeetingSpeaker();
        speaker.setMeetingId(5L);
        speaker.setSpeakerLabel(label);
        speaker.setDisplayName(displayName);
        return speaker;
    }
}
