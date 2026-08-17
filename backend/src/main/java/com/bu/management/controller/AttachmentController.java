package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.CreateAttachmentDTO;
import com.bu.management.entity.Attachment;
import com.bu.management.service.AttachmentService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 附件控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "附件管理", description = "附件管理接口")
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
@RequirePermission({"requirement:list", "task:list", "issue:list"})
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 创建附件
     */
    @Operation(summary = "创建附件", description = "创建附件记录")
    @PostMapping
    @RequirePermission({"requirement:edit", "task:edit", "issue:edit"})
    public Result<Attachment> createAttachment(@RequestBody CreateAttachmentDTO dto) {
        Attachment attachment = attachmentService.createAttachment(dto);
        return Result.success(attachment);
    }

    /**
     * 查询附件详情
     */
    @Operation(summary = "查询附件详情", description = "根据附件ID查询详情")
    @GetMapping("/{id}")
    public Result<Attachment> getAttachment(@Parameter(description = "附件ID") @PathVariable Long id) {
        Attachment attachment = attachmentService.getAttachmentById(id);
        return Result.success(attachment);
    }

    /**
     * 查询关联对象的附件列表
     */
    @Operation(summary = "查询关联对象的附件", description = "根据关联类型和关联ID查询所有附件")
    @GetMapping("/related/{relatedType}/{relatedId}")
    public Result<List<Attachment>> getAttachmentsByRelated(
            @Parameter(description = "关联类型") @PathVariable String relatedType,
            @Parameter(description = "关联ID") @PathVariable Long relatedId) {
        List<Attachment> attachments = attachmentService.getAttachmentsByRelated(relatedType, relatedId);
        return Result.success(attachments);
    }

    /**
     * 删除附件
     */
    @Operation(summary = "删除附件", description = "删除附件记录")
    @DeleteMapping("/{id}")
    @RequirePermission({"requirement:edit", "task:edit", "issue:edit"})
    public Result<Void> deleteAttachment(@Parameter(description = "附件ID") @PathVariable Long id) {
        attachmentService.deleteAttachment(id);
        return Result.success();
    }
}
