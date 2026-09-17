package com.bu.management.vo;

import java.time.LocalDate;

/** 会议列表行。 */
public record MeetingListItem(
        Long id,
        String title,
        LocalDate meetingDate,
        Integer durationSeconds,
        String status,
        String generationError) {
}
