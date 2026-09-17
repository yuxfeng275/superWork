package com.bu.management.vo;

import com.bu.management.service.MeetingTranscriptCodec;
import java.time.LocalDate;
import java.util.List;

/** 会议详情：转写（corrected 优先）+ 说话人 + 摘要 + 待办。 */
public record MeetingDetailView(
        Long id,
        String title,
        LocalDate meetingDate,
        Long projectId,
        Integer durationSeconds,
        String status,
        String generationModel,
        String generationError,
        List<MeetingTranscriptCodec.Segment> segments,
        List<MeetingSpeakerView> speakers,
        MeetingSummaryView summary,
        List<MeetingTodoView> todos) {
}
