package com.bu.management.vo;

import java.time.LocalDateTime;

public record EmailIntegrationTestResponse(boolean success, String message, LocalDateTime testedAt) {
}
