package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.ProjectProfitAllocationBatchRequest;
import com.bu.management.entity.ProjectProfitAllocation;
import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.service.BusinessLineProfitService;
import com.bu.management.service.ProjectProfitService;
import com.bu.management.vo.ProjectProfitMonthSyncVO;
import com.bu.management.vo.ProjectProfitReportVO;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "项目利润表", description = "月份 × 业务线 × 分类 × 项目 利润表（合计取工时系统镜像，与业务线利润对齐）")
@RestController
@RequestMapping("/api/finance/project-profit")
@RequiredArgsConstructor
public class ProjectProfitController {

    private final ProjectProfitService projectProfitService;
    private final BusinessLineProfitService profitService;

    @GetMapping
    @Operation(summary = "项目利润报表（by月 / H1 / H2 / 全年，多选筛选）")
    @RequirePermission({"project-profit:view"})
    public Result<ProjectProfitReportVO> query(@RequestParam(required = false) Integer year,
                                               @RequestParam(required = false) List<Integer> months,
                                               @RequestParam(required = false) List<String> periods,
                                               @RequestParam(required = false) List<Long> businessLineIds,
                                               @RequestParam(required = false) List<String> categories,
                                               @RequestParam(required = false) List<Long> projectIds) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success(projectProfitService.query(targetYear, months, periods,
                businessLineIds, categories, projectIds));
    }

    @PostMapping("/sync-month")
    @Operation(summary = "月度一键同步：工时+成本重拉 → 业务线利润镜像刷新 → 当月对齐结果")
    @RequirePermission({"project-profit:manage"})
    public Result<ProjectProfitMonthSyncVO> syncMonth(@RequestBody Map<String, Object> body) {
        Object yearMonth = body == null ? null : body.get("yearMonth");
        if (yearMonth == null || yearMonth.toString().isBlank()) {
            throw new IllegalArgumentException("yearMonth 不能为空");
        }
        return Result.success(projectProfitService.syncMonth(yearMonth.toString().trim()));
    }

    @PostMapping("/sync")
    @Operation(summary = "业务线利润镜像同步（month 优先，否则整年逐月；整年回填用）")
    @RequirePermission({"project-profit:manage"})
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
    @RequirePermission({"project-profit:view"})
    public Result<List<WorktimeSyncLog>> syncLogs(@RequestParam(defaultValue = "10") int limit) {
        return Result.success(profitService.recentSyncLogs(limit));
    }

    @GetMapping("/allocations")
    @Operation(summary = "成本手动分配列表（月 × 业务线）")
    @RequirePermission({"project-profit:view"})
    public Result<List<ProjectProfitAllocation>> listAllocations(@RequestParam(required = false) String yearMonth,
                                                                 @RequestParam(required = false) Long businessLineId) {
        return Result.success(projectProfitService.listAllocations(yearMonth, businessLineId));
    }

    @PostMapping("/allocations/batch")
    @Operation(summary = "按 (月, 项目, 成本类型) 唯一键 upsert 分配")
    @RequirePermission({"project-profit:manage"})
    public Result<List<ProjectProfitAllocation>> saveAllocations(
            @RequestBody ProjectProfitAllocationBatchRequest request) {
        return Result.success(projectProfitService.saveAllocations(request));
    }

    @DeleteMapping("/allocations/{id}")
    @Operation(summary = "删除分配")
    @RequirePermission({"project-profit:manage"})
    public Result<Void> deleteAllocation(@PathVariable Long id) {
        projectProfitService.deleteAllocation(id);
        return Result.success();
    }
}
