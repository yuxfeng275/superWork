package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailSyncStatus(
    String status, LocalDateTime startedAt, LocalDateTime completedAt, int count, String message) {}
