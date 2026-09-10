package com.bu.management.service;

import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.EmailAction;
import com.bu.management.entity.Issue;
import com.bu.management.entity.Task;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.entity.EmailMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.entity.Project;
import com.bu.management.exception.ResourceNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 摘要条目一键转化：
 * - 待办/重要 → 任务（issueType 派生自转化类型；默认挂当前用户、来源邮件所属项目）
 * - 风险 → 事项（Issue，issueType=问题，severity 由优先级映射）
 * - 待办/风险/重要 → 大事儿（BU Key Matter）
 * 转化即登记闭环记录；目标完成后由各业务服务回写 CLOSED。
 */
@Service
@RequiredArgsConstructor
public class EmailActionService {

    private final EmailMessageMapper messageMapper;
    private final EmailActionLinkService linkService;
    private final TaskService taskService;
    private final IssueService issueService;
    private final BuKeyMatterService keyMatterService;
    private final com.bu.management.mapper.UserMapper userMapper;

    public record ConvertResult(String actionType, Long targetId, String targetTitle, boolean created) {}

    @Transactional
    public ConvertResult convertToTask(Long ownerUserId, Long messageId, String itemKind,
            String itemTitle, Long requirementId, Long assigneeId) {
        requireOwnedMessage(ownerUserId, messageId);
        EmailAction existing = linkService.register(ownerUserId, messageId, itemKind, itemTitle,
                "TASK", -1L, itemTitle);
        if (!Long.valueOf(-1L).equals(existing.getTargetId())) {
            return new ConvertResult("TASK", existing.getTargetId(), existing.getTargetTitle(), false);
        }
        Task task = new Task();
        task.setRequirementId(requirementId);
        task.setTitle(trim(itemTitle, 200));
        task.setDescription(sourceDescription(ownerUserId, messageId, itemTitle));
        task.setAssigneeId(assigneeId != null ? assigneeId : ownerUserId);
        task.setTaskType("开发任务");
        Task created = taskService.createTask(toDto(task), ownerUserId);
        EmailAction action = linkService.register(ownerUserId, messageId, itemKind, itemTitle,
                "TASK", created.getId(), created.getTitle());
        return new ConvertResult("TASK", created.getId(), created.getTitle(), true);
    }

    @Transactional
    public ConvertResult convertToIssue(Long ownerUserId, Long messageId, String itemKind,
            String itemTitle, String severity, Long assigneeId) {
        requireOwnedMessage(ownerUserId, messageId);
        EmailAction existing = linkService.register(ownerUserId, messageId, itemKind, itemTitle,
                "ISSUE", -1L, itemTitle);
        if (!Long.valueOf(-1L).equals(existing.getTargetId())) {
            return new ConvertResult("ISSUE", existing.getTargetId(), existing.getTargetTitle(), false);
        }
        com.bu.management.dto.CreateIssueDTO dto = new com.bu.management.dto.CreateIssueDTO();
        dto.setTitle(trim(itemTitle, 200));
        dto.setDescription(sourceDescription(ownerUserId, messageId, itemTitle));
        dto.setIssueType("问题");
        dto.setSeverity(severity == null ? "中" : severity);
        dto.setAssigneeId(assigneeId != null ? assigneeId : ownerUserId);
        Issue created = issueService.createIssue(dto, ownerUserId);
        linkService.register(ownerUserId, messageId, itemKind, itemTitle, "ISSUE",
                created.getId(), created.getTitle());
        return new ConvertResult("ISSUE", created.getId(), created.getTitle(), true);
    }

    @Transactional
    public ConvertResult convertToKeyMatter(Long ownerUserId, Long messageId, String itemKind,
            String itemTitle, Long projectId) {
        requireOwnedMessage(ownerUserId, messageId);
        EmailAction existing = linkService.register(ownerUserId, messageId, itemKind, itemTitle,
                "KEY_MATTER", -1L, itemTitle);
        if (!Long.valueOf(-1L).equals(existing.getTargetId())) {
            return new ConvertResult("KEY_MATTER", existing.getTargetId(), existing.getTargetTitle(), false);
        }
        com.bu.management.dto.BuKeyMatterRequest request = new com.bu.management.dto.BuKeyMatterRequest();
        request.setTitle(trim(itemTitle, 200));
        request.setDescription(sourceDescription(ownerUserId, messageId, itemTitle));
        request.setOwnerId(ownerUserId);
        request.setProjectId(projectId);
        request.setPriority("高");
        request.setStatus("进行中");
        request.setProgress(0);
        BuKeyMatter created = keyMatterService.create(request, ownerUserId, username(ownerUserId));
        linkService.register(ownerUserId, messageId, itemKind, itemTitle, "KEY_MATTER",
                created.getId(), created.getTitle());
        return new ConvertResult("KEY_MATTER", created.getId(), created.getTitle(), true);
    }

    /** 某邮件的全部转化/闭环记录。 */
    public List<EmailAction> actions(Long ownerUserId, Long messageId) {
        return linkService.byMessage(ownerUserId, messageId);
    }

    private com.bu.management.dto.CreateTaskDTO toDto(Task task) {
        com.bu.management.dto.CreateTaskDTO dto = new com.bu.management.dto.CreateTaskDTO();
        dto.setRequirementId(task.getRequirementId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setAssigneeId(task.getAssigneeId());
        dto.setTaskType(task.getTaskType());
        return dto;
    }

    private String sourceDescription(Long ownerUserId, Long messageId, String itemTitle) {
        EmailMessage message = messageMapper.selectById(messageId);
        if (message == null || !ownerUserId.equals(message.getOwnerUserId())) {
            return "来自邮件 #" + messageId;
        }
        String preview = message.getBodyText() == null ? "" : message.getBodyText();
        if (preview.length() > 1500) {
            preview = preview.substring(0, 1500) + "…";
        }
        return "来自邮件「" + (message.getSubject() == null ? "(无主题)" : message.getSubject())
                + "」（#" + messageId + "）\n发件人：" + message.getSenderAddress()
                + "\n待办：" + itemTitle + "\n\n---- 邮件正文摘录 ----\n" + preview;
    }


    private String username(Long userId) {
        // 大事儿 access service 需要用户名；此处取系统用户表
        com.bu.management.entity.User user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }


    private void requireOwnedMessage(Long ownerUserId, Long messageId) {
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) {
            throw new ResourceNotFoundException("邮件不存在");
        }
    }

    private String trim(String value, int max) {
        if (value == null) return "邮件待办";
        return value.length() > max ? value.substring(0, max) : value;
    }
}
