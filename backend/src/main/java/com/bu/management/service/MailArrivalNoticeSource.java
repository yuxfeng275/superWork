package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 新邮件到达通知源：绑定邮箱且最近 24h 内有新邮件时提醒。
 * 通知当日已读后不再重复；查询失败降级为无通知。
 */
@Component
@RequiredArgsConstructor
public class MailArrivalNoticeSource {

    public static final String KIND = "MAIL_ARRIVAL";

    private final EmailAccountMapper emailAccountMapper;
    private final EmailMessageMapper emailMessageMapper;

    public AiNoticeService.Notice compute(Long userId, LocalDate today) {
        try {
            Long accounts = emailAccountMapper.selectCount(new LambdaQueryWrapper<EmailAccount>()
                    .eq(EmailAccount::getOwnerUserId, userId));
            if (accounts == null || accounts == 0) {
                return null;
            }
            LocalDateTime since = today.minusDays(1).atStartOfDay();
            List<EmailMessage> recent = emailMessageMapper.selectList(
                    new LambdaQueryWrapper<EmailMessage>()
                            .eq(EmailMessage::getOwnerUserId, userId)
                            .ge(EmailMessage::getReceivedAt, since)
                            .orderByDesc(EmailMessage::getReceivedAt)
                            .last("LIMIT 5"));
            if (recent.isEmpty()) {
                return null;
            }
            String preview = recent.get(0).getSubject() == null ? "(无主题)"
                    : recent.get(0).getSubject();
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            return new AiNoticeService.Notice(KIND,
                    "最近 24 小时收到 " + recent.size() + " 封新邮件",
                    "最新一封：" + preview + "。可以在 AI 助手里让我搜索或摘要邮件。",
                    "/emails",
                    today,
                    false);
        } catch (Exception e) {
            return null;
        }
    }
}
