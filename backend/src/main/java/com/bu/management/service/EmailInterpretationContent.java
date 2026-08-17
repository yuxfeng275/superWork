package com.bu.management.service;

public record EmailInterpretationContent(
        String summary,
        String senderIntent,
        String keyPointsJson,
        String actionItemsJson,
        String risksJson,
        String replySuggestion) {
}
