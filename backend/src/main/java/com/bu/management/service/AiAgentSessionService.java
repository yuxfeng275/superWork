package com.bu.management.service;

import com.bu.management.entity.AiAgentSession;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiAgentSessionMapper;
import com.bu.management.vo.AiAgentSessionSummary;
import com.bu.management.vo.AiAgentSessionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手会话服务：CRUD + 消息持久化（messages_json 为 AgentMessage JSON 数组）。
 */
@Service
@RequiredArgsConstructor
public class AiAgentSessionService {

    public static final String DEFAULT_TITLE = "新的对话";
    public static final String DEFAULT_PROVIDER = AiAgentModelConfigService.PROVIDER_ZHIPU;
    public static final String DEFAULT_MODEL = "glm-5.3";
    public static final String PROVIDER_DEEPSEEK = AiAgentModelConfigService.PROVIDER_DEEPSEEK;
    private static final int MAX_TITLE_LENGTH = 30;

    private final AiAgentSessionMapper sessionMapper;
    private final AiAgentModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 当前用户的会话摘要列表，按更新时间倒序。
     */
    public List<AiAgentSessionSummary> list(Long userId) {
        return sessionMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiAgentSession>()
                                .eq(AiAgentSession::getOwnerUserId, userId)
                                .orderByDesc(AiAgentSession::getUpdatedAt))
                .stream()
                .map(session -> new AiAgentSessionSummary(session.getId(), session.getTitle(),
                        session.getProvider(), session.getModel(), session.getUpdatedAt(),
                        messageCount(session)))
                .toList();
    }

    /**
     * 会话详情（含解析后的消息数组）；仅归属人可见。
     */
    public AiAgentSessionView get(Long userId, Long id) {
        AiAgentSession session = requireOwned(userId, id);
        return new AiAgentSessionView(session.getId(), session.getTitle(), session.getProvider(),
                session.getModel(), session.getUpdatedAt(), parseMessages(session));
    }

    /**
     * 创建会话；provider 支持 zhipu/deepseek（缺省 zhipu），
     * model 缺省取系统配置中该 provider 的模型，运行时按 provider 取凭据。
     */
    public AiAgentSessionView create(Long userId, String title, String provider, String model) {
        String resolvedProvider = PROVIDER_DEEPSEEK.equals(provider) ? PROVIDER_DEEPSEEK : DEFAULT_PROVIDER;
        String resolvedModel = StringUtils.hasText(model) ? model.trim()
                : modelConfigService.resolveModelConfig(resolvedProvider).model();
        AiAgentSession session = new AiAgentSession();
        session.setOwnerUserId(userId);
        session.setTitle(StringUtils.hasText(title) ? title.trim() : DEFAULT_TITLE);
        session.setProvider(resolvedProvider);
        session.setModel(resolvedModel);
        session.setMessagesJson(null);
        sessionMapper.insert(session);
        return toView(session);
    }

    /**
     * 删除会话；仅归属人可删。
     */
    public void delete(Long userId, Long id) {
        AiAgentSession session = requireOwned(userId, id);
        sessionMapper.deleteById(session.getId());
    }

    /**
     * 追加消息并持久化；标题为默认值时用首条用户消息自动生成（≤30 字）。
     * newMessagesJson 为 AgentMessage JSON 数组（旧→新）。
     */
    public AiAgentSession appendMessages(Long id, String newMessagesJson) {
        AiAgentSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        ArrayNode messages = parseMessages(session);
        appendArray(messages, newMessagesJson);
        if (DEFAULT_TITLE.equals(session.getTitle())) {
            String title = deriveTitle(messages);
            if (StringUtils.hasText(title)) {
                session.setTitle(title);
            }
        }
        session.setMessagesJson(messages.toString());
        sessionMapper.updateById(session);
        return session;
    }

    private AiAgentSession requireOwned(Long userId, Long id) {
        AiAgentSession session = sessionMapper.selectById(id);
        if (session == null || !userId.equals(session.getOwnerUserId())) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return session;
    }

    private AiAgentSessionView toView(AiAgentSession session) {
        return new AiAgentSessionView(session.getId(), session.getTitle(), session.getProvider(),
                session.getModel(), session.getUpdatedAt(), parseMessages(session));
    }

    private int messageCount(AiAgentSession session) {
        return parseMessages(session).size();
    }

    private ArrayNode parseMessages(AiAgentSession session) {
        String json = session.getMessagesJson();
        if (!StringUtils.hasText(json)) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node instanceof ArrayNode array) {
                return array;
            }
            return objectMapper.createArrayNode();
        } catch (Exception e) {
            // 消息数据损坏时按空会话处理，不阻断使用
            return objectMapper.createArrayNode();
        }
    }

    private void appendArray(ArrayNode target, String newMessagesJson) {
        if (!StringUtils.hasText(newMessagesJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(newMessagesJson);
            if (node instanceof ArrayNode array) {
                target.addAll(array);
            } else {
                target.add(node);
            }
        } catch (Exception ignored) {
            // 侧车返回了非法 JSON：跳过，保留已有消息
        }
    }

    /**
     * 取第一条用户消息：字符串 content 直接用，内容片段取首个 text 部分。
     */
    private String deriveTitle(ArrayNode messages) {
        for (JsonNode message : messages) {
            if (!"user".equals(message.path("role").asText())) {
                continue;
            }
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                return truncate(content.asText());
            }
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                        return truncate(part.get("text").asText());
                    }
                }
            }
            return null;
        }
        return null;
    }

    private String truncate(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_TITLE_LENGTH ? trimmed : trimmed.substring(0, MAX_TITLE_LENGTH);
    }

    /**
     * 当前毫秒时间戳，用于 AgentMessage timestamp 字段。
     */
    public static long nowEpochMillis() {
        return Instant.now().toEpochMilli();
    }
}
