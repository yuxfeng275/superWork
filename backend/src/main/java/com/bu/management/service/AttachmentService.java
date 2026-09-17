package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.CreateAttachmentDTO;
import com.bu.management.entity.Attachment;
import com.bu.management.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 附件服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentMapper attachmentMapper;

    /**
     * 创建附件
     */
    /** Creates an attachment using the authenticated actor, never a body actor. */
    @Transactional
    public Attachment createAttachment(CreateAttachmentDTO dto, Long actorId) {
        if (actorId == null) {
            throw new RuntimeException("当前登录用户不存在");
        }
        Attachment attachment = new Attachment();
        attachment.setRelatedType(dto.getRelatedType());
        attachment.setRelatedId(dto.getRelatedId());
        attachment.setFileName(dto.getFileName());
        attachment.setFilePath(dto.getFilePath());
        attachment.setFileSize(dto.getFileSize());
        attachment.setFileType(dto.getFileType());
        attachment.setUploadedBy(actorId);
        attachment.setCreatedAt(LocalDateTime.now());

        attachmentMapper.insert(attachment);
        return attachment;
    }

    /**
     * 查询附件详情
     */
    public Attachment getAttachmentById(Long id) {
        return attachmentMapper.selectById(id);
    }

    /**
     * 查询关联对象的附件列表
     */
    public List<Attachment> getAttachmentsByRelated(String relatedType, Long relatedId) {
        LambdaQueryWrapper<Attachment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Attachment::getRelatedType, relatedType);
        wrapper.eq(Attachment::getRelatedId, relatedId);
        wrapper.orderByDesc(Attachment::getCreatedAt);
        return attachmentMapper.selectList(wrapper);
    }

    /**
     * 删除附件
     */
    @Transactional
    public void deleteAttachment(Long id) {
        attachmentMapper.deleteById(id);
    }
}
