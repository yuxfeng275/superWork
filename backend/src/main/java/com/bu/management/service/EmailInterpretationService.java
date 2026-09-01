package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailMessage;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.vo.EmailInterpretationView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailInterpretationService {
    private final EmailMessageMapper messageMapper;
    private final DeepSeekDigestClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public EmailInterpretationView generate(Long ownerUserId, Long messageId) {
        EmailMessage message = requireOwned(ownerUserId, messageId);
        if ("GENERATING".equals(message.getAiInterpretationStatus())) return toView(message);
        message.setAiInterpretationStatus("GENERATING");
        message.setAiInterpretationError(null);
        messageMapper.updateById(message);
        try {
            EmailInterpretationContent content = deepSeekClient.interpret(message, ownerUserId,
                    threadContext(message));
            LocalDateTime now = LocalDateTime.now();
            message.setAiInterpretationStatus("SUCCESS");
            message.setAiInterpretationJson(objectMapper.writeValueAsString(Map.of(
                    "disposition", content.disposition() == null ? "" : content.disposition(),
                    "summary", content.summary(),
                    "senderIntent", content.senderIntent(),
                    "keyPoints", objectMapper.readTree(content.keyPointsJson()),
                    "actionItems", objectMapper.readTree(content.actionItemsJson()),
                    "risks", objectMapper.readTree(content.risksJson()),
                    "replySuggestion", content.replySuggestion())));
            message.setAiInterpretationModel(deepSeekClient.configuredModel());
            message.setAiInterpretationError(null);
            message.setAiInterpretedAt(now);
            messageMapper.updateById(message);
            return toView(message);
        } catch (Exception exception) {
            message.setAiInterpretationStatus("FAILED");
            message.setAiInterpretationError(sanitize(exception.getMessage()));
            message.setAiInterpretedAt(LocalDateTime.now());
            messageMapper.updateById(message);
            return toView(message);
        }
    }

    /** 同主题历史邮件（最多 3 封，旧→新）：长线程里未回答的问题往往在上面 */
    private List<EmailMessage> threadContext(EmailMessage message) {
        if (message.getSubject() == null || message.getSubject().isBlank()) return java.util.List.of();
        String normalized = message.getSubject()
                .replaceAll("(?i)^(\\s*(re|fw|fwd|回复|转发|答复)[:：]\\s*)+", "")
                .trim();
        if (normalized.isBlank()) return java.util.List.of();
        List<EmailMessage> candidates = messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, message.getOwnerUserId())
                .like(EmailMessage::getSubject, normalized)
                .lt(EmailMessage::getReceivedAt, message.getReceivedAt())
                .orderByDesc(EmailMessage::getReceivedAt)
                .last("limit 3"));
        java.util.Collections.reverse(candidates);
        return candidates;
    }

    public EmailInterpretationView get(Long ownerUserId, Long messageId) {
        return toView(requireOwned(ownerUserId, messageId));
    }

    public EmailInterpretationView toView(EmailMessage message) {
        String status = message.getAiInterpretationStatus() == null
                ? "NOT_GENERATED" : message.getAiInterpretationStatus();
        JsonNode content = parseObject(message.getAiInterpretationJson());
        return new EmailInterpretationView(
                status,
                text(content, "disposition"),
                text(content, "summary"),
                text(content, "senderIntent"),
                array(content, "keyPoints"),
                array(content, "actionItems"),
                array(content, "risks"),
                text(content, "replySuggestion"),
                message.getAiInterpretationModel(),
                message.getAiInterpretationError(),
                message.getAiInterpretedAt());
    }

    private EmailMessage requireOwned(Long ownerUserId, Long messageId) {
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) throw new ResourceNotFoundException("邮件不存在");
        return message;
    }

    private JsonNode parseObject(String value) {
        if (value == null || value.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(value);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode array(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isArray() ? value : objectMapper.createArrayNode();
    }

    private String text(JsonNode parent, String field) {
        return parent.path(field).isTextual() ? parent.path(field).asText() : null;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "AI 解读失败，请稍后重试";
        return value.substring(0, Math.min(500, value.length()));
    }
}
