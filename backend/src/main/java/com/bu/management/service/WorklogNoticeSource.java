package com.bu.management.service;

import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.WorkLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工时缺填通知源：检查昨天（工作日）是否有工时记录。
 * 数据来自本地 work_log 表（云效同步链路已覆盖），查询失败降级为无通知。
 */
@Component
@RequiredArgsConstructor
public class WorklogNoticeSource {

    private final WorkLogMapper workLogMapper;
    private final WorktimeAnalyticsService worktimeAnalyticsService;
    /** 通知类型编码（与 ai_notice_read.notice_kind 对应）。 */
    public static final String KIND = "WORKLOG_MISSING";

    public AiNoticeService.Notice compute(Long userId, LocalDate today) {
        try {
            LocalDate target = lastWorkday(today);
            Long count = workLogMapper.selectCount(new LambdaQueryWrapper<WorkLog>()
                    .eq(WorkLog::getUserId, userId)
                    .eq(WorkLog::getWorkDate, target));
            if (count != null && count > 0) {
                return null;
            }
            // 交叉校验：工时系统上月同步数据里是否有这个人（有则填报习惯正常，缺昨天仅提醒；
            // 无则可能未接入工时系统，不重复打扰）
            boolean inWorktime = worktimeAnalyticsService.resolveIdentity(userId) != null;
            if (!inWorktime) {
                return null;
            }
            return new AiNoticeService.Notice(KIND,
                    "工时缺填提醒",
                    target + " 的工时尚未填写。可让 AI 助手分析你的月度工时趋势并确认缺填情况。",
                    "/ai-assistant",
                    today,
                    false);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate lastWorkday(LocalDate today) {
        LocalDate day = today.minusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.minusDays(1);
        }
        return day;
    }
}
