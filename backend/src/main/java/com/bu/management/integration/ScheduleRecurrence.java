package com.bu.management.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 企微日程的周期规则展开（日历视图必需）。
 *
 * <p>企微 {@code calendar schedules list} 对周期日程返回的是**系列**（begin_time 为系列首次时间），
 * 而不是窗口内的每次场次；日历要按天展示必须自行展开。规则字段见 CLI schema
 * {@code ScheduleRecurrence}：repeat_type ∈ daily/weekly/monthly/monthly_on_the_nth_day/yearly/
 * yearly_on_the_nth_day/work_day，另有 repeat_interval、repeat_day_of_week(MO..SU)、
 * repeat_day_of_month、repeat_month_of_year、repeat_time(次数，0=不限)、repeat_until、exception[]。
 *
 * @author BU Team
 * @since 2026-09-18
 */
@Slf4j
public final class ScheduleRecurrence {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_ITERATIONS = 5_000;
    private static final int MAX_OCCURRENCES = 400;

    private ScheduleRecurrence() {
    }

    /** 一次场次（开始时间 + 结束时间）。 */
    public record Occurrence(LocalDateTime start, LocalDateTime end) {}

    /**
     * 展开一条日程在 [windowStart, windowEnd] 内的所有场次。
     *
     * @param beginTime 系列首次开始时间
     * @param endTime   系列首次结束时间
     * @param repeat    周期规则节点（可为 null / 非周期）
     */
    public static List<Occurrence> expand(LocalDateTime beginTime, LocalDateTime endTime,
            JsonNode repeat, LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (beginTime == null) return List.of();
        Duration duration = endTime == null || endTime.isBefore(beginTime)
                ? Duration.ZERO : Duration.between(beginTime, endTime);
        if (repeat == null || !repeat.path("is_repeat").asBoolean(false)) {
            return intersects(beginTime, windowStart, windowEnd)
                    ? List.of(new Occurrence(beginTime, beginTime.plus(duration))) : List.of();
        }

        String type = repeat.path("repeat_type").asText("weekly");
        int interval = Math.max(1, repeat.path("repeat_interval").asInt(1));
        int limit = repeat.path("repeat_time").asInt(0);
        LocalDateTime until = parse(repeat.path("repeat_until").asText(null));
        LocalDateTime effectiveEnd = until != null && until.isBefore(windowEnd) ? until : windowEnd;
        Set<String> exceptions = exceptions(repeat);

        List<LocalDateTime> starts = candidateStarts(beginTime, type, interval, repeat, effectiveEnd);
        List<Occurrence> occurrences = new ArrayList<>();
        int generated = 0;
        int iterations = 0;
        for (LocalDateTime start : starts) {
            if (++iterations > MAX_ITERATIONS || occurrences.size() >= MAX_OCCURRENCES) {
                log.debug("日程展开达到上限：type={}, window={}~{}", type, windowStart, windowEnd);
                break;
            }
            if (limit > 0 && generated >= limit) break;
            generated++;
            if (start.isAfter(effectiveEnd)) break;
            if (exceptions.contains(start.format(TIME))) continue;
            if (!intersects(start, windowStart, windowEnd)) continue;
            occurrences.add(new Occurrence(start, start.plus(duration)));
        }
        return occurrences;
    }

    /** 生成候选开始时间（升序，覆盖窗口起点之前的一系列时间以保证区间相交判定正确）。 */
    private static List<LocalDateTime> candidateStarts(LocalDateTime base, String type, int interval,
            JsonNode repeat, LocalDateTime windowEnd) {
        List<LocalDateTime> starts = new ArrayList<>();
        switch (type) {
            case "daily" -> {
                for (LocalDateTime cursor = base; !cursor.isAfter(windowEnd); cursor = cursor.plusDays(interval)) {
                    starts.add(cursor);
                }
            }
            case "work_day" -> {
                LocalDateTime cursor = base;
                while (!cursor.isAfter(windowEnd)) {
                    DayOfWeek day = cursor.getDayOfWeek();
                    if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) starts.add(cursor);
                    cursor = cursor.plusDays(1);
                }
            }
            case "weekly" -> {
                List<DayOfWeek> weekdays = weekdays(repeat);
                if (weekdays.isEmpty()) weekdays = List.of(base.getDayOfWeek());
                LocalDateTime weekAnchor = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDateTime week = weekAnchor; !week.isAfter(windowEnd); week = week.plusWeeks(interval)) {
                    for (DayOfWeek day : weekdays) {
                        LocalDateTime candidate = week.with(TemporalAdjusters.nextOrSame(day))
                                .withHour(base.getHour()).withMinute(base.getMinute()).withSecond(base.getSecond());
                        if (!candidate.isBefore(base)) starts.add(candidate);
                    }
                }
            }
            case "monthly" -> {
                for (LocalDateTime month = base.withDayOfMonth(1); !month.isAfter(windowEnd);
                        month = month.plusMonths(interval)) {
                    addIfValidDate(starts, month.getYear(), month.getMonthValue(), base.getDayOfMonth(), base);
                }
            }
            case "monthly_on_the_nth_day" -> {
                List<Integer> days = daysOfMonth(repeat, base.getDayOfMonth());
                for (LocalDateTime month = base.withDayOfMonth(1); !month.isAfter(windowEnd);
                        month = month.plusMonths(interval)) {
                    for (int day : days) {
                        addIfValidDate(starts, month.getYear(), month.getMonthValue(), day, base);
                    }
                }
            }
            case "yearly" -> {
                for (int year = base.getYear(); year <= windowEnd.getYear(); year += interval) {
                    addIfValidDate(starts, year, base.getMonthValue(), base.getDayOfMonth(), base);
                }
            }
            case "yearly_on_the_nth_day" -> {
                List<Integer> months = monthsOfYear(repeat, base.getMonthValue());
                List<Integer> days = daysOfMonth(repeat, base.getDayOfMonth());
                for (int year = base.getYear(); year <= windowEnd.getYear(); year += interval) {
                    for (int month : months) {
                        for (int day : days) {
                            addIfValidDate(starts, year, month, day, base);
                        }
                    }
                }
            }
            default -> starts.add(base);
        }
        starts.removeIf(start -> start.isBefore(base));
        starts.sort(LocalDateTime::compareTo);
        return starts;
    }

    private static void addIfValidDate(List<LocalDateTime> starts, int year, int month, int day,
            LocalDateTime template) {
        try {
            LocalDate date = LocalDate.of(year, month, day);
            starts.add(date.atTime(template.toLocalTime()));
        } catch (Exception e) {
            // 该月无此日期（如 2/30）：跳过，与主流日历一致
        }
    }

    private static List<DayOfWeek> weekdays(JsonNode repeat) {
        List<DayOfWeek> days = new ArrayList<>();
        for (JsonNode node : repeat.path("repeat_day_of_week")) {
            switch (node.asText("").toUpperCase()) {
                case "MO" -> days.add(DayOfWeek.MONDAY);
                case "TU" -> days.add(DayOfWeek.TUESDAY);
                case "WE" -> days.add(DayOfWeek.WEDNESDAY);
                case "TH" -> days.add(DayOfWeek.THURSDAY);
                case "FR" -> days.add(DayOfWeek.FRIDAY);
                case "SA" -> days.add(DayOfWeek.SATURDAY);
                case "SU" -> days.add(DayOfWeek.SUNDAY);
                default -> { }
            }
        }
        return days;
    }

    private static List<Integer> daysOfMonth(JsonNode repeat, int fallback) {
        List<Integer> days = new ArrayList<>();
        for (JsonNode node : repeat.path("repeat_day_of_month")) {
            int day = node.asInt(0);
            if (day >= 1 && day <= 31) days.add(day);
        }
        if (days.isEmpty()) days.add(fallback);
        return days;
    }

    private static List<Integer> monthsOfYear(JsonNode repeat, int fallback) {
        List<Integer> months = new ArrayList<>();
        for (JsonNode node : repeat.path("repeat_month_of_year")) {
            int month = node.asInt(0);
            if (month >= 1 && month <= 12) months.add(month);
        }
        if (months.isEmpty()) months.add(fallback);
        return months;
    }

    private static Set<String> exceptions(JsonNode repeat) {
        Set<String> set = new HashSet<>();
        for (JsonNode node : repeat.path("exception")) {
            String begin = node.path("begin_time").asText("");
            if (StringUtils.hasText(begin)) set.add(begin);
        }
        return set;
    }

    /** 场次是否与窗口相交（含跨天场次）。 */
    private static boolean intersects(LocalDateTime start, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return !start.isAfter(windowEnd) && !start.isBefore(windowStart);
    }

    public static LocalDateTime parse(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDateTime.parse(value.trim(), TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
