package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAction;
import com.bu.management.entity.EmailMessage;
import com.bu.management.mapper.EmailActionMapper;
import com.bu.management.mapper.EmailMessageMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮件行动闭环登记：摘要待办/风险一键转任务/事项/大事儿时落 email_action，
 * 目标完成时由 TaskService/IssueService/BuKeyMatterService 回调 markClosed 回写闭环。
 * 一条摘要条目重复转化返回已存在记录（幂等，不重复建任务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailActionLinkService {

    private final EmailActionMapper actionMapper;
    private final EmailMessageMapper messageMapper;

    /**
     * 幂等登记转化。同一 (owner, messageId, itemKind, itemTitle) 已存在时直接返回既有记录。
     */
    @Transactional
    public EmailAction register(Long ownerUserId, Long messageId, String itemKind,
            String itemTitle, String actionType, Long targetId, String targetTitle) {
        EmailAction existing = actionMapper.selectOne(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .eq(EmailAction::getMessageId, messageId)
                .eq(EmailAction::getItemKind, itemKind)
                .eq(EmailAction::getItemTitle, itemTitle)
                .eq(EmailAction::getActionType, actionType)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) {
            throw new com.bu.management.exception.ResourceNotFoundException("邮件不存在");
        }
        EmailAction action = new EmailAction();
        action.setOwnerUserId(ownerUserId);
        action.setMessageId(messageId);
        action.setItemKind(itemKind);
        action.setItemTitle(itemTitle);
        action.setActionType(actionType);
        action.setTargetId(targetId);
        action.setTargetTitle(targetTitle);
        action.setStatus("OPEN");
        action.setCreatedAt(LocalDateTime.now());
        actionMapper.insert(action);
        return action;
    }

    /**
     * 闭环回写：把 (actionType, targetId) 的 OPEN 记录置 CLOSED。
     * 由任务/事项/大事儿完成时调用；失败只记日志，不影响业务方提交。
     */
    public void markClosed(String actionType, Long targetId) {
        try {
            List<EmailAction> open = actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                    .eq(EmailAction::getActionType, actionType)
                    .eq(EmailAction::getTargetId, targetId)
                    .eq(EmailAction::getStatus, "OPEN"));
            LocalDateTime now = LocalDateTime.now();
            for (EmailAction action : open) {
                action.setStatus("CLOSED");
                action.setClosedAt(now);
                actionMapper.updateById(action);
            }
        } catch (Exception e) {
            log.warn("邮件闭环回写失败: type={}, target={}, error={}", actionType, targetId, e.getMessage());
        }
    }

    /** 某用户某邮件的转化记录（详情页展示闭环状态）。 */
    public List<EmailAction> byMessage(Long ownerUserId, Long messageId) {
        return actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .eq(EmailAction::getMessageId, messageId)
                .orderByDesc(EmailAction::getId));
    }

    /** 摘要条目的闭环状态：key = itemKind|itemTitle，value = CLOSED 时的时间。 */
    public Map<String, LocalDateTime> closedByItem(Long ownerUserId, List<Long> messageIds) {
        Map<String, LocalDateTime> result = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return result;
        }
        List<EmailAction> actions = actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .in(EmailAction::getMessageId, messageIds)

                .eq(EmailAction::getStatus, "CLOSED"));
        for (EmailAction action : actions) {
            result.putIfAbsent(action.getItemKind() + "|" + action.getItemTitle(), action.getClosedAt());
        }
        return result;
    }

    /** 未闭环条目 key 集合（次日摘要去重/标注）。 */
    public java.util.Set<String> openItemKeys(Long ownerUserId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (EmailAction action : actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .eq(EmailAction::getStatus, "OPEN"))) {
            keys.add(action.getItemKind() + "|" + action.getItemTitle());
        }
        return keys;
    }
    /** 全部已闭环条目的 key 集合（TODO|标题 / RISK|标题），供摘要闭环快照统计。 */
    public java.util.Set<String> closedItemKeys(Long ownerUserId) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (EmailAction action : actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .eq(EmailAction::getStatus, "CLOSED"))) {
            keys.add(action.getItemKind() + "|" + action.getItemTitle());
        }
        return keys;
    }
}
