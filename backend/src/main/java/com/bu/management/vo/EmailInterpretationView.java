package com.bu.management.vo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record EmailInterpretationView(
        String status,
        String disposition,
        String summary,
        String senderIntent,
        JsonNode keyPoints,
        JsonNode actionItems,
        JsonNode risks,
        String replySuggestion,
        String model,
        String errorMessage,
        LocalDateTime generatedAt) {
}
