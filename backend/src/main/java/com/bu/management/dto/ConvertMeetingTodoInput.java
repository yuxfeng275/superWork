package com.bu.management.dto;

/** 待办转化：actionType=TASK|ISSUE；requirementId 允许为空（独立任务）。 */
public record ConvertMeetingTodoInput(
        String actionType,
        Long requirementId,
        Long assigneeId,
        String severity,
        String taskType) {
}
