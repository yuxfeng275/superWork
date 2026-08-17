package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.BuDashboardService;
import com.bu.management.vo.BuDashboardResponse;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "BU驾驶舱", description = "重点方向、人员负荷和工时完整性")
@RestController
@RequestMapping("/api/bu-dashboard")
@RequiredArgsConstructor
@RequirePermission({"bu:dashboard:view"})
public class BuDashboardController {

    private final BuDashboardService dashboardService;

    @GetMapping
    @Operation(summary = "获取BU负责人驾驶舱")
    public Result<BuDashboardResponse> getDashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") Integer planWindowWorkdays) {
        LocalDate effectiveEnd = endDate == null ? LocalDate.now() : endDate;
        LocalDate effectiveStart = startDate == null ? effectiveEnd.withDayOfMonth(1) : startDate;
        if (effectiveEnd.isBefore(effectiveStart)) {
            throw new RuntimeException("统计结束日期不能早于开始日期");
        }
        int window = Math.max(1, Math.min(60, planWindowWorkdays));
        return Result.success(dashboardService.getDashboard(effectiveStart, effectiveEnd, window));
    }
}
