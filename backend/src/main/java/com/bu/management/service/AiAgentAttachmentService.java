package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.AiAgentAttachmentProperties;
import com.bu.management.entity.AiAgentAttachment;
import com.bu.management.entity.AiAgentSession;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiAgentAttachmentMapper;
import com.bu.management.mapper.AiAgentSessionMapper;
import com.bu.management.vo.AiAgentAttachmentVO;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * AI 助手会话附件：上传落盘 + 会话级归属校验 + 发送时的文本摘要注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentAttachmentService {

    private final AiAgentAttachmentMapper attachmentMapper;
    private final AiAgentSessionMapper sessionMapper;
    private final AiAgentAttachmentStorage storage;
    private final AiAgentAttachmentProperties properties;

    /** 上传附件到指定会话；仅会话归属人可传。 */
    public AiAgentAttachmentVO upload(Long userId, Long sessionId, MultipartFile file) {
        requireOwnedSession(userId, sessionId);
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("请选择要上传的附件");
        }
        long maxBytes = Math.max(1, properties.getMaxSizeMb()) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new RuntimeException("附件超过大小上限（" + properties.getMaxSizeMb() + "MB）");
        }
        AiAgentAttachmentStorage.StoredAttachment stored;
        try (var in = file.getInputStream()) {
            stored = storage.store(sessionId, file.getOriginalFilename(), in);
        } catch (IOException e) {
            throw new RuntimeException("附件保存失败：" + e.getMessage());
        }
        AiAgentAttachment attachment = new AiAgentAttachment();
        attachment.setSessionId(sessionId);
        attachment.setOwnerUserId(userId);
        attachment.setFileName(file.getOriginalFilename() == null
                ? "未命名" : file.getOriginalFilename());
        attachment.setContentType(file.getContentType());
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setSha256(stored.sha256());
        attachment.setRelativePath(stored.relativePath());
        attachmentMapper.insert(attachment);
        return toVo(attachment);
    }

    /** 会话附件列表（仅归属人可见）。 */
    public List<AiAgentAttachmentVO> list(Long userId, Long sessionId) {
        requireOwnedSession(userId, sessionId);
        return attachmentMapper.selectList(new LambdaQueryWrapper<AiAgentAttachment>()
                        .eq(AiAgentAttachment::getSessionId, sessionId)
                        .orderByAsc(AiAgentAttachment::getId))
                .stream()
                .map(this::toVo)
                .toList();
    }

    /**
     * 发送消息时注入给模型的附件上下文：文本类带正文摘要，二进制只带文件名与大小。
     * 附件必须属于当前会话。
     */
    public String composeContext(Long sessionId, List<Long> attachmentIds) {
        List<AiAgentAttachment> attachments = requireSessionAttachments(sessionId, attachmentIds);
        StringBuilder context = new StringBuilder();
        for (AiAgentAttachment attachment : attachments) {
            context.append("【附件 ").append(attachment.getFileName()).append("】\n");
            String excerpt = storage.excerpt(attachment.getRelativePath(), attachment.getFileName());
            if (excerpt != null && !excerpt.isBlank()) {
                context.append(excerpt);
            } else {
                context.append("（二进制文件，共 ")
                        .append(humanSize(attachment.getSizeBytes()))
                        .append("，正文未注入）");
            }
            context.append("\n\n");
        }
        return context.toString().stripTrailing();
    }

    /** 持久化到消息历史里的紧凑展示标记：[附件: 文件名]。 */
    public String displayMarkers(Long sessionId, List<Long> attachmentIds) {
        List<AiAgentAttachment> attachments = requireSessionAttachments(sessionId, attachmentIds);
        StringBuilder markers = new StringBuilder();
        for (AiAgentAttachment attachment : attachments) {
            if (markers.length() > 0) {
                markers.append(' ');
            }
            markers.append("[附件: ").append(attachment.getFileName()).append(']');
        }
        return markers.toString();
    }

    private List<AiAgentAttachment> requireSessionAttachments(Long sessionId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<AiAgentAttachment> attachments = attachmentMapper.selectBatchIds(ids);
        if (attachments.size() != ids.stream().distinct().count()) {
            throw new RuntimeException("附件不存在或已被删除");
        }
        for (AiAgentAttachment attachment : attachments) {
            if (!sessionId.equals(attachment.getSessionId())) {
                throw new RuntimeException("附件不属于当前会话");
            }
        }
        return attachments;
    }

    private AiAgentSession requireOwnedSession(Long userId, Long sessionId) {
        AiAgentSession session = sessionMapper.selectById(sessionId);
        if (session == null || !userId.equals(session.getOwnerUserId())) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return session;
    }

    private String humanSize(Long sizeBytes) {
        long bytes = sizeBytes == null ? 0 : sizeBytes;
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0);
    }

    private AiAgentAttachmentVO toVo(AiAgentAttachment attachment) {
        return new AiAgentAttachmentVO(attachment.getId(), attachment.getFileName(),
                attachment.getContentType(), attachment.getSizeBytes(), attachment.getCreatedAt());
    }
}
