package com.bu.management.service;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAction;
import com.bu.management.entity.EmailDailyDigest;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailSentReply;
import com.bu.management.mapper.EmailActionMapper;
import com.bu.management.mapper.EmailDailyDigestMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailSentReplyMapper;
import com.bu.management.vo.EmailActionMetrics;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 价值度量（整改 P2-5）：本月转化数、闭环率、摘要好评率、平均响应时长。
 * 全部按 owner_user_id 隔离，只统计本人数据。
 */
@Service
@RequiredArgsConstructor
public class EmailValueService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final EmailActionMapper actionMapper;
    private final EmailDailyDigestMapper digestMapper;
    private final EmailSentReplyMapper replyMapper;
    private final EmailMessageMapper messageMapper;

    public EmailActionMetrics monthlyMetrics(Long ownerUserId) {
        LocalDate monthStart = YearMonth.now(ZONE).atDay(1);
        LocalDateTime from = monthStart.atStartOfDay();
        List<EmailAction> actions = actionMapper.selectList(new LambdaQueryWrapper<EmailAction>()
                .eq(EmailAction::getOwnerUserId, ownerUserId)
                .ge(EmailAction::getCreatedAt, from));
        long converted = actions.size();
        long closed = actions.stream().filter(a -> "CLOSED".equals(a.getStatus())).count();
        List<EmailDailyDigest> digests = digestMapper.selectList(new LambdaQueryWrapper<EmailDailyDigest>()
                .eq(EmailDailyDigest::getOwnerUserId, ownerUserId)
                .ge(EmailDailyDigest::getDigestDate, monthStart));
        long useful = digests.stream().filter(d -> "USEFUL".equals(d.getFeedback())).count();
        long useless = digests.stream().filter(d -> "USELESS".equals(d.getFeedback())).count();
        List<EmailSentReply> replies = replyMapper.selectList(new LambdaQueryWrapper<EmailSentReply>()
                .eq(EmailSentReply::getOwnerUserId, ownerUserId)
                .eq(EmailSentReply::getStatus, "SENT")
                .ge(EmailSentReply::getSentAt, from));
        return new EmailActionMetrics(monthStart, converted, closed,
                converted == 0 ? null : (double) closed / converted,
                digests.size(), useful, useless, averageResponseMinutes(ownerUserId, actions, replies));
    }

    /** 平均响应时长 = 回复/转化时刻 − 原邮件接收时刻（分钟；无样本为 null）。 */
    private Double averageResponseMinutes(Long ownerUserId,
            List<EmailAction> actions, List<EmailSentReply> replies) {
        List<Long> minutes = new ArrayList<>();
        for (EmailSentReply reply : replies) {
            addLatency(minutes, ownerUserId, reply.getMessageId(), reply.getSentAt());
        }
        for (EmailAction action : actions) {
            addLatency(minutes, ownerUserId, action.getMessageId(), action.getCreatedAt());
        }
        if (minutes.isEmpty()) return null;
        return minutes.stream().mapToLong(Long::longValue).average().orElse(0d);
    }

    private void addLatency(List<Long> minutes, Long ownerUserId, Long messageId, LocalDateTime respondedAt) {
        if (respondedAt == null) return;
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null || message.getReceivedAt() == null) return;
        long mins = Duration.between(message.getReceivedAt(), respondedAt).toMinutes();
        if (mins >= 0) minutes.add(mins);
    }
}
