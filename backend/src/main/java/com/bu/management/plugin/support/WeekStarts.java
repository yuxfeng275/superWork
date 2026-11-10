package com.bu.management.plugin.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.springframework.util.StringUtils;

/**
 * 周报「周一」的解析口径：与 AiAgentToolService#parseWeekStart 逐字一致。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class WeekStarts {

    private WeekStarts() {
    }

    /** 缺省本周一；显式传入必须就是周一。 */
    public static LocalDate parseWeekStart(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDate.now().with(DayOfWeek.MONDAY);
        }
        LocalDate date = LocalDate.parse(value);
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 必须是周一");
        }
        return date;
    }
}
