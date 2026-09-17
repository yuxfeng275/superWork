package com.bu.management.dto;

/**
 * 人工校正稿的一行：时间戳由服务端从原始转写继承，edited 由服务端比对置位。
 */
public record MeetingSegmentInput(Integer seq, String speaker, String text) {
}
