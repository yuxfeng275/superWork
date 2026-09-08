package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiNoticeRead;
import com.bu.management.mapper.AiNoticeReadMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 站内通知聚合服务：实时计算工时缺填 / OA 待办 / 邮箱未绑定等提醒，
 * 已读状态落 ai_notice_read（user+kind+date 幂等）。
 * 外部系统故障一律降级为"无该类通知"，不影响面板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNoticeService {

    /** 聚合后的通知条目（content 为实时计算，不落库）。 */
    public record Notice(String kind, String title, String body, String link, LocalDate date,
            boolean read) {}
    private final AiNoticeReadMapper readMapper;
    private final WorklogNoticeSource worklogSource;
    private final OaNoticeSource oaSource;
    private final MailNoticeSource mailSource;
    private final MailArrivalNoticeSource mailArrivalSource;

    /** 当前用户的通知列表（已读的也返回，前端可折叠展示）。 */
    public List<Notice> list(Long userId) {
        LocalDate today = LocalDate.now();
        List<Notice> notices = new ArrayList<>();
        collect(notices, worklogSource.compute(userId, today));
        collect(notices, oaSource.compute(userId, today));
        collect(notices, mailSource.compute(userId, today));
        collect(notices, mailArrivalSource.compute(userId, today));
        markRead(notices, userId);
        return notices;
    }

    public int unreadCount(Long userId) {
        return (int) list(userId).stream().filter(n -> !n.read()).count();
    }

    /** 标记某类通知（当日）已读。 */
    public void markRead(Long userId, String kind, LocalDate date) {
        AiNoticeRead row = new AiNoticeRead();
        row.setUserId(userId);
        row.setNoticeKind(kind);
        row.setNoticeDate(date);
        try {
            readMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 已读幂等
        }
    }

    private void collect(List<Notice> target, Notice notice) {
        if (notice != null) {
            target.add(notice);
        }
    }

    private void markRead(List<Notice> notices, Long userId) {
        if (notices.isEmpty()) {
            return;
        }
        Set<String> keys = notices.stream()
                .map(n -> n.kind() + "|" + n.date())
                .collect(Collectors.toSet());
        Map<String, Boolean> readMap = new LinkedHashMap<>();
        for (String key : keys) {
            String[] parts = key.split("\\|", 2);
            readMap.put(key, readMapper.selectCount(new LambdaQueryWrapper<AiNoticeRead>()
                    .eq(AiNoticeRead::getUserId, userId)
                    .eq(AiNoticeRead::getNoticeKind, parts[0])
                    .eq(AiNoticeRead::getNoticeDate, LocalDate.parse(parts[1]))) > 0);
        }
        notices.replaceAll(n -> new Notice(n.kind(), n.title(), n.body(), n.link(), n.date(),
                Boolean.TRUE.equals(readMap.get(n.kind() + "|" + n.date()))));
    }
}
