package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailConnectionTestResponse(
    boolean success, String message, LocalDateTime testedAt) {}
