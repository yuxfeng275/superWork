package com.bu.management.service;

public record DigestContent(
    String overview,
    String topicItems,
    String progressItems,
    String importantItems,
    String todoItems,
    String riskItems,
    String replyItems,
    boolean fallback) {}
