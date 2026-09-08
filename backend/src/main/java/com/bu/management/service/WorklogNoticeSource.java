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

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final WorkLogMapper workLogMapper;

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
            return new AiNoticeService.Notice(KIND,
                    "工时缺填提醒",
                    target + " 的工时尚未填写，请在「BU驾驶舱 → 工时管理」补填，避免影响统计。",
                    "/tasks",
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
