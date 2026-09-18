package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.ScheduleCalendarService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日程中心：合并本地会议与企微日程的统一日历数据源（日历视图 / 列表模式共用）。
 *
 * @author BU Team
 * @since 2026-09-18
 */
@Tag(name = "日程", description = "本地会议 + 企微日程的统一日历视图与列表数据")
@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
@RequirePermission({"schedule:view"})
public class ScheduleController {

    private final ScheduleCalendarService service;

    @GetMapping("/events")
    @Operation(summary = "按区间查询日历事件（本地会议 + 企微日程）")
    public Result<ScheduleCalendarService.CalendarResponse> events(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sources) {
        Set<String> wanted = new LinkedHashSet<>();
        if (StringUtils.hasText(sources)) {
            Arrays.stream(sources.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(String::toUpperCase)
                    .forEach(wanted::add);
        }
        return Result.success(service.events(from, to, wanted, userId));
    }
}
