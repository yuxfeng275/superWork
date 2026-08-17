package com.bu.management.vo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record EmailDigestResponse(
    Long id,
    LocalDate businessDate,
    String status,
    String generationMode,
    String generatedModel,
    String overview,
    int mailCount,
    JsonNode importantItems,
    JsonNode todos,
    JsonNode risks,
    JsonNode replySuggestions,
    LocalDateTime generatedAt,
    String pushStatus,
    String pushMessage) {}
