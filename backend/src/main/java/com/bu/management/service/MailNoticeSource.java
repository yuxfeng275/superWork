package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.mapper.EmailAccountMapper;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 邮箱未绑定通知源：当前用户尚未绑定邮箱时提醒。
 * 查询失败降级为无通知。
 */
@Component
@RequiredArgsConstructor
public class MailNoticeSource {

    public static final String KIND = "EMAIL_UNBOUND";

    private final EmailAccountMapper emailAccountMapper;

    public AiNoticeService.Notice compute(Long userId, LocalDate today) {
        try {
            Long count = emailAccountMapper.selectCount(new LambdaQueryWrapper<EmailAccount>()
                    .eq(EmailAccount::getOwnerUserId, userId));
            if (count != null && count > 0) {
                return null;
            }
            return new AiNoticeService.Notice(KIND,
                    "邮箱未绑定",
                    "绑定邮箱后，AI 助手可以帮你搜索和阅读已同步的邮件。",
                    "/emails",
                    today,
                    false);
        } catch (Exception e) {
            return null;
        }
    }
}
