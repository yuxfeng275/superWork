package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Meeting;
import com.bu.management.integration.ScheduleRecurrence;
import com.bu.management.integration.WeComCliClient;
import com.bu.management.mapper.MeetingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 日程聚合：把**本地会议**（会议模块的录音/纪要记录）与**企微日程**（wecom-cli 日历，含周期展开）
 * 合并成统一的日历事件流，供「日程」页的日历视图与列表模式共用。
 *
 * <p>数据边界：本地会议按上传人（本人）可见，与会议模块 v1 口径一致；企微日程取机器人授权身份可见范围
 * （含共享日历）。企微侧失败（未授权/超时）不阻断本地会议展示，只在响应里给出 hint。
 *
 * @author BU Team
 * @since 2026-09-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleCalendarService {

    /** 企微日程查询窗口限制：只能查当天前后 30 天。 */
    private static final int WECOM_WINDOW_DAYS = 30;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 事件来源。 */
    public static final String SOURCE_MEETING = "MEETING";
    public static final String SOURCE_WECOM_SCHEDULE = "WECOM_SCHEDULE";
    public static final String SOURCE_WECOM_MEETING = "WECOM_MEETING";

    private final MeetingMapper meetingMapper;
    private final WeComCliClient cli;

    /** 统一日历事件。 */
    public record CalendarEvent(
            String id,
            String source,
            String title,
            String start,
            String end,
            boolean allDay,
            String location,
            String organizer,
            String calendarName,
            List<String> participants,
            String status,
            Long meetingId,
            String scheduleId,
            String meetingCode,
            String meetingLink,
            boolean recurring,
            String description) {}

    /** 聚合响应：事件 + 企微侧提示（未授权时不阻断）。 */
    public record CalendarResponse(List<CalendarEvent> events, List<String> hints, String rangeStart,
            String rangeEnd) {}

    private record CacheEntry(List<CalendarEvent> events, String hint, long at) {}

    private final Map<String, CacheEntry> scheduleCache = new ConcurrentHashMap<>();

    /**
     * 聚合查询：[from, to] 闭区间（按天）。
     *
     * @param sources 需要的事件来源（空 = 全部）
     */
    public CalendarResponse events(LocalDate from, LocalDate to, Set<String> sources, Long userId) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;
        if (end.isBefore(start)) end = start;
        Set<String> wanted = sources == null || sources.isEmpty()
                ? Set.of(SOURCE_MEETING, SOURCE_WECOM_SCHEDULE, SOURCE_WECOM_MEETING) : sources;

        List<CalendarEvent> events = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        if (wanted.contains(SOURCE_MEETING)) {
            events.addAll(localMeetings(start, end, userId));
        }
        if (wanted.contains(SOURCE_WECOM_SCHEDULE) || wanted.contains(SOURCE_WECOM_MEETING)) {
            LocalDateTime windowStart = start.atStartOfDay();
            LocalDateTime windowEnd = end.plusDays(1).atStartOfDay();
            if (wanted.contains(SOURCE_WECOM_SCHEDULE)) {
                WecomResult result = wecomSchedules(windowStart, windowEnd);
                events.addAll(result.events());
                if (result.hint() != null) hints.add(result.hint());
            }
            if (wanted.contains(SOURCE_WECOM_MEETING)) {
                WecomResult result = wecomMeetings(windowStart, windowEnd);
                events.addAll(result.events());
                if (result.hint() != null) hints.add(result.hint());
            }
        }
        events.sort((a, b) -> a.start().compareTo(b.start()));
        return new CalendarResponse(events, hints, start.toString(), end.toString());
    }

    // ==================== 本地会议 ====================

    private List<CalendarEvent> localMeetings(LocalDate start, LocalDate end, Long userId) {
        if (userId == null) return List.of();
        List<Meeting> meetings = meetingMapper.selectList(new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getOwnerUserId, userId)
                .between(Meeting::getMeetingDate, start, end)
                .orderByAsc(Meeting::getMeetingDate));
        List<CalendarEvent> events = new ArrayList<>();
        for (Meeting meeting : meetings) {
            LocalDate date = meeting.getMeetingDate();
            if (date == null) continue;
            Duration duration = meeting.getDurationSeconds() == null
                    ? Duration.ZERO : Duration.ofSeconds(meeting.getDurationSeconds());
            // 本地会议只有日期粒度（无起始时刻）→ 作为全天事件渲染
            events.add(new CalendarEvent(
                    "meeting:" + meeting.getId(), SOURCE_MEETING, meeting.getTitle(),
                    date.atStartOfDay().format(TIME), date.atStartOfDay().plus(duration).format(TIME),
                    true, null, null, "会议模块", List.of(), meeting.getStatus(),
                    meeting.getId(), null, null, null, false, null));
        }
        return events;
    }

    // ==================== 企微日程 ====================

    private record WecomResult(List<CalendarEvent> events, String hint) {}

    private WecomResult wecomSchedules(LocalDateTime windowStart, LocalDateTime windowEnd) {
        LocalDate today = LocalDate.now();
        LocalDate minDate = today.minusDays(WECOM_WINDOW_DAYS);
        LocalDate maxDate = today.plusDays(WECOM_WINDOW_DAYS);
        LocalDate queryStart = windowStart.toLocalDate().isBefore(minDate) ? minDate : windowStart.toLocalDate();
        LocalDate queryEnd = windowEnd.toLocalDate().isAfter(maxDate) ? maxDate : windowEnd.toLocalDate();
        String hint = null;
        if (!queryStart.equals(windowStart.toLocalDate()) || !queryEnd.equals(windowEnd.toLocalDate())) {
            hint = "企微日程仅支持查询当天前后 30 天，已按 " + queryStart + " ~ " + queryEnd + " 取数";
        }
        if (queryEnd.isBefore(queryStart)) {
            return new WecomResult(List.of(), "所选区间超出企微日程可查询范围（当天前后 30 天）");
        }

        String cacheKey = queryStart + "|" + queryEnd;
        CacheEntry cached = scheduleCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL.toMillis()) {
            return new WecomResult(cached.events(), cached.hint() != null ? cached.hint() : hint);
        }

        List<CalendarEvent> events = new ArrayList<>();
        try {
            JsonNode payload = cli.execOrThrow("calendar", List.of(
                    "schedules", "list",
                    "--begin-time", queryStart.atStartOfDay().format(TIME),
                    "--end-time", queryEnd.plusDays(1).atStartOfDay().format(TIME)));
            for (JsonNode item : payload.path("schedule_list")) {
                LocalDateTime begin = ScheduleRecurrence.parse(item.path("begin_time").asText(null));
                LocalDateTime end = ScheduleRecurrence.parse(item.path("end_time").asText(null));
                if (begin == null) continue;
                List<ScheduleRecurrence.Occurrence> occurrences =
                        ScheduleRecurrence.expand(begin, end, item.path("repeat_rule"), windowStart, windowEnd);
                boolean recurring = item.path("repeat_rule").path("is_repeat").asBoolean(false);
                for (ScheduleRecurrence.Occurrence occurrence : occurrences) {
                    events.add(scheduleEvent(item, occurrence, recurring));
                }
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? "企微日程读取失败" : e.getMessage();
            log.info("企微日程读取失败：{}", message);
            return new WecomResult(List.of(), message);
        }
        scheduleCache.put(cacheKey, new CacheEntry(events, hint, System.currentTimeMillis()));
        return new WecomResult(events, hint);
    }

    private CalendarEvent scheduleEvent(JsonNode item, ScheduleRecurrence.Occurrence occurrence, boolean recurring) {
        List<String> participants = new ArrayList<>();
        for (JsonNode attendee : item.path("attendees")) {
            String name = attendee.path("name").asText(attendee.path("display_name").asText(""));
            if (StringUtils.hasText(name)) participants.add(name);
        }
        String location = item.path("location").asText("");
        String room = item.path("meeting_room").path("meeting_room_name").asText("");
        if (StringUtils.hasText(room)) {
            location = StringUtils.hasText(location) ? location + " · " + room : room;
        }
        JsonNode meeting = item.path("meeting");
        String meetingCode = meeting.path("meeting_code").asText(null);
        String meetingLink = meeting.path("meeting_link").asText(null);
        boolean allDay = item.path("is_all_day").asBoolean(false);
        String scheduleId = item.path("schedule_id").asText("");
        return new CalendarEvent(
                "schedule:" + scheduleId + "@" + occurrence.start().format(TIME),
                SOURCE_WECOM_SCHEDULE,
                item.path("subject").asText(""),
                occurrence.start().format(TIME),
                occurrence.end().format(TIME),
                allDay,
                StringUtils.hasText(location) ? location : null,
                StringUtils.hasText(item.path("creator_name").asText("")) ? item.path("creator_name").asText("") : null,
                item.path("calendar_name").asText(null),
                participants,
                null, null, scheduleId,
                StringUtils.hasText(meetingCode) ? meetingCode : null,
                StringUtils.hasText(meetingLink) ? meetingLink : null,
                recurring,
                truncate(item.path("description").asText("")));
    }

    // ==================== 企微会议（含纪要/录制，需「会议」品类授权） ====================

    private WecomResult wecomMeetings(LocalDateTime windowStart, LocalDateTime windowEnd) {
        try {
            JsonNode payload = cli.execOrThrow("meeting", List.of(
                    "list",
                    "--begin-time", windowStart.format(TIME),
                    "--end-time", windowEnd.format(TIME),
                    "--limit", "50"));
            List<CalendarEvent> events = new ArrayList<>();
            for (String field : List.of("attended_meetings", "created_meetings")) {
                for (JsonNode item : payload.path(field)) {
                    LocalDateTime begin = ScheduleRecurrence.parse(item.path("begin_time").asText(null));
                    LocalDateTime end = ScheduleRecurrence.parse(item.path("end_time").asText(null));
                    if (begin == null) continue;
                    if (begin.isBefore(windowStart) || begin.isAfter(windowEnd)) continue;
                    String meetingId = item.path("meeting_id").asText("");
                    events.add(new CalendarEvent(
                            "wemeeting:" + meetingId + (item.path("sub_meeting_id").asText("").isEmpty()
                                    ? "" : "-" + item.path("sub_meeting_id").asText("")),
                            SOURCE_WECOM_MEETING,
                            item.path("subject").asText(""),
                            begin.format(TIME),
                            (end == null ? begin.plusHours(1) : end).format(TIME),
                            false,
                            StringUtils.hasText(item.path("location").asText(""))
                                    ? item.path("location").asText("") : null,
                            item.path("creator_name").asText(null),
                            "企微会议",
                            List.of(),
                            null, null, null, null, null, false, null));
                }
            }
            return new WecomResult(events, null);
        } catch (Exception e) {
            log.info("企微会议读取失败：{}", e.getMessage());
            return new WecomResult(List.of(), "企微会议未读取：" + e.getMessage());
        }
    }

    private String truncate(String text) {
        if (!StringUtils.hasText(text)) return null;
        String trimmed = text.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }
}
