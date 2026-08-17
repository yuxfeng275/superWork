package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailIntegrationRuntimeConfig;
import com.bu.management.entity.EmailDailyDigest;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.EmailDailyDigestMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import com.bu.management.vo.EmailDigestResponse;
import com.bu.management.vo.EmailWeComMappingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;

import jakarta.annotation.Resource;

@Service
@RequiredArgsConstructor
public class EmailDigestService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final EmailDailyDigestMapper digestMapper;
    private final EmailMessageMapper messageMapper;
    private final EmailWeComMappingMapper mappingMapper;
    private final EmailAccountMapperBridge accountBridge;
    private final EmailSyncService syncService;
    private final DeepSeekDigestClient deepSeekClient;
    private final RuleEmailDigestGenerator fallbackGenerator;
    private final WeComClient weComClient;
    private final EmailIntegrationConfigService integrationConfigService;
    private final ObjectMapper objectMapper;

    @Resource(name = "emailTaskExecutor")
    private Executor taskExecutor;

    @Scheduled(cron = "${email.digest-cron:0 0 8 * * *}", zone = "Asia/Shanghai")
    public void generateYesterdayForAll() {
        LocalDate yesterday = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        accountBridge.enabledOwners().forEach(owner -> {
            syncService.syncAccount(owner.accountId(), owner.ownerUserId());
            generate(owner.ownerUserId(), yesterday);
        });
    }

    @Transactional
    public EmailDailyDigest generate(Long ownerUserId, LocalDate date) {
        LocalDate effectiveDate = normalizeDate(date);
        List<EmailMessage> messages = loadMessages(ownerUserId, effectiveDate);
        DigestContent content;
        String generationError = null;
        if (messages.isEmpty()) {
            content = fallbackGenerator.generate(messages);
        } else {
            try {
                content = deepSeekClient.generate(messages, ownerUserId);
            } catch (RuntimeException exception) {
                generationError = sanitize(exception.getMessage());
                content = fallbackGenerator.generate(messages);
            }
        }

        EmailDailyDigest digest = find(ownerUserId, effectiveDate);
        boolean insert = digest == null;
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (insert) {
            digest = new EmailDailyDigest();
            digest.setOwnerUserId(ownerUserId);
            digest.setDigestDate(effectiveDate);
            digest.setCreatedAt(now);
            digest.setPushAttempts(0);
        }
        digest.setMessageCount(messages.size());
        digest.setStatus(messages.isEmpty() ? "EMPTY" : content.fallback() ? "DEGRADED" : "SUCCESS");
        digest.setGenerationMode(messages.isEmpty() ? "NONE" : content.fallback() ? "RULES" : "AI");
        digest.setGeneratedModel(content.fallback() ? null : deepSeekClient.configuredModel());
        digest.setOverview(content.overview());
        digest.setImportantItems(content.importantItems());
        digest.setTodoItems(content.todoItems());
        digest.setRiskItems(content.riskItems());
        digest.setReplyItems(content.replyItems());
        digest.setErrorMessage(generationError);
        digest.setPushStatus(messages.isEmpty() ? "NOT_REQUIRED" : "PENDING");
        digest.setPushError(null);
        digest.setUpdatedAt(now);
        if (insert) {
            digestMapper.insert(digest);
        } else {
            digestMapper.updateById(digest);
        }
        if (!messages.isEmpty()) {
            tryPush(ownerUserId, digest);
        }
        return digest;
    }

    public EmailDigestResponse startRegeneration(Long ownerUserId, LocalDate date) {
        LocalDate effectiveDate = normalizeDate(date);
        int messageCount = loadMessages(ownerUserId, effectiveDate).size();
        EmailDailyDigest digest = find(ownerUserId, effectiveDate);
        boolean insert = digest == null;
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (insert) {
            digest = new EmailDailyDigest();
            digest.setOwnerUserId(ownerUserId);
            digest.setDigestDate(effectiveDate);
            digest.setCreatedAt(now);
            digest.setPushAttempts(0);
        }
        digest.setMessageCount(messageCount);
        digest.setStatus("PENDING");
        digest.setGenerationMode("NONE");
        digest.setGeneratedModel(null);
        digest.setOverview("");
        digest.setImportantItems("[]");
        digest.setTodoItems("[]");
        digest.setRiskItems("[]");
        digest.setReplyItems("[]");
        digest.setErrorMessage(null);
        digest.setPushStatus("PENDING");
        digest.setPushError(null);
        digest.setUpdatedAt(now);
        if (insert) {
            digestMapper.insert(digest);
        } else {
            digestMapper.updateById(digest);
        }
        taskExecutor.execute(() -> generate(ownerUserId, effectiveDate));
        return toResponse(digest);
    }

    public EmailDigestResponse getResponse(Long ownerUserId, LocalDate date) {
        LocalDate effectiveDate = normalizeDate(date);
        EmailDailyDigest digest = find(ownerUserId, effectiveDate);
        if (digest == null) {
            int messageCount = loadMessages(ownerUserId, effectiveDate).size();
            return pendingResponse(effectiveDate, messageCount);
        }
        return toResponse(digest);
    }

    public EmailDigestResponse generateResponse(Long ownerUserId, LocalDate date) {
        return toResponse(generate(ownerUserId, date));
    }

    public EmailDigestResponse retryPushResponse(Long ownerUserId, Long digestId) {
        EmailDailyDigest digest = digestMapper.selectOne(new LambdaQueryWrapper<EmailDailyDigest>()
                .eq(EmailDailyDigest::getId, digestId)
                .eq(EmailDailyDigest::getOwnerUserId, ownerUserId));
        if (digest == null) {
            throw new ResourceNotFoundException("摘要不存在");
        }
        tryPush(ownerUserId, digest);
        return toResponse(digestMapper.selectById(digestId));
    }

    public EmailWeComMappingStatus getMappingStatus(Long ownerUserId) {
        EmailWeComMapping mapping = findMapping(ownerUserId);
        return mapping == null
                ? new EmailWeComMappingStatus(false, false, null)
                : new EmailWeComMappingStatus(true, Integer.valueOf(1).equals(mapping.getEnabled()),
                        mapping.getWecomUserId());
    }

    @Transactional
    public EmailWeComMappingStatus saveMappingStatus(Long ownerUserId, String userId, boolean enabled) {
        EmailWeComMapping mapping = findMapping(ownerUserId);
        boolean insert = mapping == null;
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (insert) {
            mapping = new EmailWeComMapping();
            mapping.setOwnerUserId(ownerUserId);
            mapping.setCreatedAt(now);
        }
        mapping.setWecomUserId(userId.trim());
        mapping.setEnabled(enabled ? 1 : 0);
        mapping.setUpdatedAt(now);
        if (insert) {
            mappingMapper.insert(mapping);
        } else {
            mappingMapper.updateById(mapping);
        }
        return getMappingStatus(ownerUserId);
    }

    private void tryPush(Long ownerUserId, EmailDailyDigest digest) {
        EmailIntegrationRuntimeConfig integration = integrationConfigService.getRuntimeConfig();
        if (!integration.isWeComConfigured()) {
            digest.setPushStatus("NOT_CONFIGURED");
            digest.setPushError(null);
            digestMapper.updateById(digest);
            return;
        }
        EmailWeComMapping mapping = findMapping(ownerUserId);
        if (mapping == null || !Integer.valueOf(1).equals(mapping.getEnabled())) {
            digest.setPushStatus("UNMAPPED");
            digest.setPushError(null);
            digestMapper.updateById(digest);
            return;
        }
        digest.setPushAttempts((digest.getPushAttempts() == null ? 0 : digest.getPushAttempts()) + 1);
        try {
            String baseUrl = integration.publicBaseUrl() == null ? ""
                    : integration.publicBaseUrl().replaceAll("/$", "");
            String detailUrl = baseUrl.isBlank() ? "" : baseUrl + "/emails?date=" + digest.getDigestDate();
            weComClient.push(mapping.getWecomUserId(), digest, detailUrl);
            digest.setPushStatus("SUCCESS");
            digest.setPushError(null);
            digest.setPushedAt(LocalDateTime.now(BUSINESS_ZONE));
        } catch (RuntimeException exception) {
            digest.setPushStatus("FAILED");
            digest.setPushError(sanitize(exception.getMessage()));
        }
        digestMapper.updateById(digest);
    }

    private EmailDailyDigest find(Long ownerUserId, LocalDate date) {
        return digestMapper.selectOne(new LambdaQueryWrapper<EmailDailyDigest>()
                .eq(EmailDailyDigest::getOwnerUserId, ownerUserId)
                .eq(EmailDailyDigest::getDigestDate, date));
    }

    private EmailWeComMapping findMapping(Long ownerUserId) {
        return mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                .eq(EmailWeComMapping::getOwnerUserId, ownerUserId));
    }

    private List<EmailMessage> loadMessages(Long ownerUserId, LocalDate date) {
        return messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId)
                .ge(EmailMessage::getReceivedAt, date.atStartOfDay())
                .lt(EmailMessage::getReceivedAt, date.plusDays(1).atStartOfDay())
                .orderByAsc(EmailMessage::getReceivedAt));
    }

    private LocalDate normalizeDate(LocalDate date) {
        return date == null ? LocalDate.now(BUSINESS_ZONE).minusDays(1) : date;
    }

    private EmailDigestResponse pendingResponse(LocalDate date, int count) {
        return new EmailDigestResponse(null, date, "PENDING", "NONE", null, null, count,
                emptyArray(), emptyArray(), emptyArray(), emptyArray(), null, "PENDING", null);
    }

    private EmailDigestResponse toResponse(EmailDailyDigest digest) {
        return new EmailDigestResponse(
                digest.getId(), digest.getDigestDate(), digest.getStatus(), digest.getGenerationMode(),
                digest.getGeneratedModel(), digest.getOverview(), digest.getMessageCount() == null ? 0 : digest.getMessageCount(),
                parseArray(digest.getImportantItems()), parseArray(digest.getTodoItems()),
                parseArray(digest.getRiskItems()), parseArray(digest.getReplyItems()),
                digest.getUpdatedAt(), digest.getPushStatus(), digest.getPushError());
    }

    private JsonNode parseArray(String value) {
        if (value == null || value.isBlank()) {
            return emptyArray();
        }
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isArray() ? node : emptyArray();
        } catch (Exception ignored) {
            return emptyArray();
        }
    }

    private JsonNode emptyArray() {
        return objectMapper.createArrayNode();
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "服务暂不可用";
        }
        return value.substring(0, Math.min(500, value.length()));
    }
}
