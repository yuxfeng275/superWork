package com.bu.management.service;

public record EmailProjectAssignment(Long messageId, Long projectId, double confidence, String reason) {
}
