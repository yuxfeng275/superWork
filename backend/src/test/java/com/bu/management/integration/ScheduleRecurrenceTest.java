package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 企微日程周期规则展开：按真实 {@code RepeatRule} 形状验证各类重复类型、间隔、例外与窗口过滤。
 */
class ScheduleRecurrenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode repeat(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> starts(LocalDateTime begin, LocalDateTime end, String repeatJson,
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        return ScheduleRecurrence.expand(begin, end, repeat(repeatJson), windowStart, windowEnd)
                .stream().map(o -> o.start().toString()).toList();
    }

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 9, 30, 23, 59);

    @Test
    @DisplayName("非周期日程：命中窗口返回一次，落在窗口外不返回")
    void singleOccurrence() {
        LocalDateTime begin = LocalDateTime.of(2026, 9, 10, 14, 0);
        assertThat(starts(begin, begin.plusHours(1), null, WINDOW_START, WINDOW_END))
                .containsExactly("2026-09-10T14:00");

        LocalDateTime outside = LocalDateTime.of(2026, 10, 10, 14, 0);
        assertThat(starts(outside, outside.plusHours(1), null, WINDOW_START, WINDOW_END)).isEmpty();
    }

    @Test
    @DisplayName("每周重复（真实样例：写周报，每周一 10:00，repeat_until 覆盖窗口）")
    void weeklySeries() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 3, 23, 10, 0), LocalDateTime.of(2026, 3, 23, 10, 15),
                """
                {"is_repeat":true,"repeat_type":"weekly","repeat_interval":1,"repeat_time":0,
                 "repeat_until":"2029-12-03 10:00:00"}
                """,
                WINDOW_START, WINDOW_END);

        assertThat(occurrences).containsExactly(
                "2026-09-07T10:00", "2026-09-14T10:00", "2026-09-21T10:00", "2026-09-28T10:00");
    }

    @Test
    @DisplayName("每两周重复：按间隔跳过一周")
    void biweeklySeries() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 9, 1, 9, 30), LocalDateTime.of(2026, 9, 1, 10, 30),
                """
                {"is_repeat":true,"repeat_type":"weekly","repeat_interval":2,"repeat_time":0}
                """,
                WINDOW_START, WINDOW_END);

        assertThat(occurrences).containsExactly(
                "2026-09-01T09:30", "2026-09-15T09:30", "2026-09-29T09:30");
    }

    @Test
    @DisplayName("自定义每周多天：按 repeat_day_of_week 展开（真实字段 MO,TU,...）")
    void customWeekdays() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 9, 1, 16, 0), LocalDateTime.of(2026, 9, 1, 17, 0),
                """
                {"is_repeat":true,"is_custom":true,"repeat_type":"weekly","repeat_interval":1,
                 "repeat_day_of_week":["MO","WE"]}
                """,
                LocalDateTime.of(2026, 9, 7, 0, 0), LocalDateTime.of(2026, 9, 14, 0, 0));

        assertThat(occurrences).containsExactly("2026-09-07T16:00", "2026-09-09T16:00");
    }

    @Test
    @DisplayName("工作日重复：周一到周五，跳过周末")
    void workDaySeries() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 9, 30),
                """
                {"is_repeat":true,"repeat_type":"work_day","repeat_interval":1}
                """,
                LocalDateTime.of(2026, 9, 11, 0, 0), LocalDateTime.of(2026, 9, 15, 23, 59));

        assertThat(occurrences).containsExactly(
                "2026-09-11T09:00", "2026-09-14T09:00", "2026-09-15T09:00");
    }

    @Test
    @DisplayName("每月重复：2 月 30 日这类无效日期跳过（与主流日历一致）")
    void monthlySeriesSkipsInvalidDates() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 1, 31, 10, 0), LocalDateTime.of(2026, 1, 31, 11, 0),
                """
                {"is_repeat":true,"repeat_type":"monthly","repeat_interval":1}
                """,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 5, 31, 23, 59));

        assertThat(occurrences).containsExactly(
                "2026-01-31T10:00", "2026-03-31T10:00", "2026-05-31T10:00");
    }

    @Test
    @DisplayName("限制次数（repeat_time）：超过次数不再展开")
    void respectsOccurrenceLimit() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 9, 30),
                """
                {"is_repeat":true,"repeat_type":"daily","repeat_interval":1,"repeat_time":3}
                """,
                WINDOW_START, WINDOW_END);

        assertThat(occurrences).containsExactly("2026-09-01T09:00", "2026-09-02T09:00", "2026-09-03T09:00");
    }

    @Test
    @DisplayName("例外场次（exception.begin_time）：对应场次被跳过")
    void skipsExceptionOccurrences() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 9, 1, 9, 0), LocalDateTime.of(2026, 9, 1, 9, 30),
                """
                {"is_repeat":true,"repeat_type":"daily","repeat_interval":1,
                 "exception":[{"begin_time":"2026-09-02 09:00:00","flag":1}]}
                """,
                WINDOW_START, WINDOW_END);

        assertThat(occurrences).startsWith("2026-09-01T09:00", "2026-09-03T09:00", "2026-09-04T09:00");
        assertThat(occurrences).doesNotContain("2026-09-02T09:00");
    }

    @Test
    @DisplayName("repeat_until 早于窗口起点：不产生场次")
    void stopsAtUntil() {
        List<String> occurrences = starts(
                LocalDateTime.of(2026, 3, 23, 10, 0), LocalDateTime.of(2026, 3, 23, 10, 15),
                """
                {"is_repeat":true,"repeat_type":"weekly","repeat_interval":1,"repeat_until":"2026-05-01 10:00:00"}
                """,
                WINDOW_START, WINDOW_END);

        assertThat(occurrences).isEmpty();
    }

    @Test
    @DisplayName("长时间跨度的无限重复不会失控（有展开上限）")
    void boundedExpansion() {
        List<String> occurrences = starts(
                LocalDateTime.of(2020, 1, 1, 8, 0), LocalDateTime.of(2020, 1, 1, 8, 30),
                """
                {"is_repeat":true,"repeat_type":"daily","repeat_interval":1,"repeat_time":0}
                """,
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0));

        assertThat(occurrences).isNotEmpty();
        assertThat(occurrences.size()).isLessThanOrEqualTo(400);
    }
}
