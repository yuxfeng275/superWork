package com.bu.management.dto;

import java.util.List;

public record MeetingTranscriptRequest(List<MeetingSegmentInput> segments) {
}
