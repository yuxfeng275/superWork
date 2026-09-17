package com.bu.management.vo;

/** 录音内说话人（匿名 label + 人工命名/映射）。 */
public record MeetingSpeakerView(String speakerLabel, String displayName, Long mappedUserId) {
}
