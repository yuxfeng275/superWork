package com.bu.management.vo;

import java.time.LocalDate;

/** 会议待办草稿行。 */
public record MeetingTodoView(
        Long id,
        String title,
        String description,
        String assigneeHint,
        String dueText,
        LocalDate dueDate,
        Integer sourceSegmentSeq,
        Long sourceStartMs,
        Long sourceEndMs,
        String sourceExcerpt,
        String status,
        String actionType,
        Long targetId,
        String targetTitle) {
}
