package com.bu.management.dto;

/** 说话人命名/映射输入；displayName/mappedUserId 传 null 即清除。 */
public record MeetingSpeakerInput(String speakerLabel, String displayName, Long mappedUserId) {
}
