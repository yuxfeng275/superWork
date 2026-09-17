package com.bu.management.dto;

import java.time.LocalDate;

/** 待办编辑：字段为 null 表示不修改；dismiss=true 直接忽略。 */
public record MeetingTodoUpdateInput(
        String title,
        String description,
        String dueText,
        LocalDate dueDate,
        Boolean dismiss) {
}
