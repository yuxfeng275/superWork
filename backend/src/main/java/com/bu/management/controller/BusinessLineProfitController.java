package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.service.BusinessLineProfitService;
import com.bu.management.vo.BizLineProfitReportVO;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "业务线利润报表", description = "业务线 × 月 真实营收与利润（数据源自工时系统，支持同步）")
@RestController
@RequestMapping("/api/finance/bl-profit")
@RequiredArgsConstructor
public class BusinessLineProfitController {

    private final BusinessLineProfitService profitService;

    @GetMapping
    @Operation(summary = "年度业务线利润报表（月行 + YTD）")
    @RequirePermission({"bl-profit:view"})
    public Result<BizLineProfitReportVO> queryYear(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success(profitService.queryYear(targetYear));
    }

    @PostMapping("/sync")
    @Operation(summary = "从工时系统同步（month 优先，否则整年逐月）")
    @RequirePermission({"bl-profit:manage"})
    public Result<List<WorktimeSyncLog>> sync(@RequestBody Map<String, Object> body) {
        Object month = body == null ? null : body.get("month");
        if (month != null && !month.toString().isBlank()) {
            return Result.success(List.of(profitService.syncMonth(month.toString(), "manual")));
        }
        Object year = body == null ? null : body.get("year");
        int targetYear = year == null ? LocalDate.now().getYear() : Integer.parseInt(year.toString());
        return Result.success(profitService.syncYear(targetYear, "manual"));
    }

    @GetMapping("/sync-logs")
    @Operation(summary = "最近同步日志")
    @RequirePermission({"bl-profit:view"})
    public Result<List<WorktimeSyncLog>> syncLogs(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(profitService.recentSyncLogs(limit));
    }
}
