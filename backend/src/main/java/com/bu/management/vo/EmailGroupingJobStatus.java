package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailGroupingJobStatus(
        String status,
        int total,
        int processed,
        int grouped,
        int ungrouped,
        String message,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
