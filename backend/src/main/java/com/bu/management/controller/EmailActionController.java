package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.EmailAction;
import com.bu.management.service.EmailActionService;
import com.bu.management.service.EmailReplyService;
import com.bu.management.vo.EmailActionMetrics;
import com.bu.management.vo.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 邮件行动闭环 API：
 * - POST /api/emails/actions/convert  摘要条目 → 任务/事项/大事儿（幂等）
 * - GET  /api/emails/actions/{messageId} 某邮件的转化与闭环记录
 * - POST /api/emails/messages/{id}/reply SMTP 发送回复草稿
 * - GET  /api/emails/messages/{id}/replies 往来发送记录
 * - POST /api/emails/digests/{date}/feedback 摘要有用/没用反馈
 * - GET  /api/emails/metrics 本月价值面板
 */
@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
@RequirePermission({"email:view"})
public class EmailActionController {

    private final EmailActionService actionService;
    private final EmailReplyService replyService;
    private final com.bu.management.service.EmailDigestService digestService;
    private final com.bu.management.service.EmailValueService valueService;

    @PostMapping("/actions/convert")
    @RequirePermission({"email:sync"})
    public Result<EmailActionService.ConvertResult> convert(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ConvertRequest request) {
        return Result.success(switch (request.getActionType()) {
            case "TASK" -> actionService.convertToTask(userId, request.getMessageId(),
                    request.getItemKind(), request.getItemTitle(),
                    request.getRequirementId(), request.getAssigneeId());
            case "ISSUE" -> actionService.convertToIssue(userId, request.getMessageId(),
                    request.getItemKind(), request.getItemTitle(),
                    request.getSeverity(), request.getAssigneeId());
            case "KEY_MATTER" -> actionService.convertToKeyMatter(userId, request.getMessageId(),
                    request.getItemKind(), request.getItemTitle(), request.getProjectId());
            default -> throw new IllegalArgumentException("actionType 仅支持 TASK/ISSUE/KEY_MATTER");
        });
    }

    @GetMapping("/actions/{messageId}")
    public Result<List<EmailAction>> actions(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long messageId) {
        return Result.success(actionService.actions(userId, messageId));
    }

    @PostMapping("/messages/{id}/reply")
    @RequirePermission({"email:sync"})
    public Result<EmailReplyService.ReplyResult> reply(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody ReplyRequest request) {
        return Result.success(replyService.send(userId, id, request.getSubject(), request.getBodyText()));
    }

    @GetMapping("/messages/{id}/replies")
    public Result<List<com.bu.management.entity.EmailSentReply>> replies(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(replyService.thread(userId, id));
    }

    @PostMapping("/digests/{date}/feedback")
    @RequirePermission({"email:sync"})
    public Result<com.bu.management.vo.EmailDigestResponse> feedback(
            @RequestAttribute("userId") Long userId,
            @PathVariable @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date,
            @Valid @RequestBody FeedbackRequest request) {
        return Result.success(digestService.saveFeedback(userId, date, request.getFeedback()));
    }

    @GetMapping("/metrics")
    public Result<EmailActionMetrics> metrics(@RequestAttribute("userId") Long userId) {
        return Result.success(valueService.monthlyMetrics(userId));
    }

    @Data
    public static class ConvertRequest {
        @NotNull
        private Long messageId;
        /** TODO/RISK/IMPORTANT/REPLY */
        @NotBlank
        private String itemKind;
        @NotBlank
        private String itemTitle;
        /** TASK/ISSUE/KEY_MATTER */
        @NotBlank
        private String actionType;
        private Long requirementId;
        private Long assigneeId;
        private String severity;
        private Long projectId;
    }

    @Data
    public static class ReplyRequest {
        private String subject;
        @NotBlank
        private String bodyText;
    }

    @Data
    public static class FeedbackRequest {
        /** USEFUL/USELESS */
        @NotBlank
        private String feedback;
    }
}
