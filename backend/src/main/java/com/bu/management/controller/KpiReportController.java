package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.KpiAlertRuleRequest;
import com.bu.management.dto.KpiNoteRequest;
import com.bu.management.dto.KpiTargetRequest;
import com.bu.management.entity.KpiAlertRule;
import com.bu.management.entity.KpiDeviationNote;
import com.bu.management.entity.KpiTarget;
import com.bu.management.entity.KpiWeeklySnapshot;
import com.bu.management.service.KpiExportService;
import com.bu.management.service.KpiReportService;
import com.bu.management.service.KpiSnapshotService;
import com.bu.management.vo.KpiReportVO;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "KPI 周报", description = "KPI 经营周报：周快照、达成率、环比预警、偏差备注")
@RestController
@RequestMapping("/api/kpi")
@RequiredArgsConstructor
public class KpiReportController {

    private final KpiReportService reportService;
    private final KpiSnapshotService snapshotService;
    private final KpiExportService exportService;

    @GetMapping("/report")
    @RequirePermission({"kpi:view"})
    @Operation(summary = "KPI 周报（年度分组行 + 全年周快照 + 备注）")
    public Result<KpiReportVO> report(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success(reportService.buildReport(targetYear));
    }

    @PostMapping("/snapshot/run")
    @RequirePermission({"kpi:manage"})
    @Operation(summary = "手动生成本周快照（默认上一个周日）")
    public Result<List<KpiWeeklySnapshot>> runSnapshot(@RequestParam(required = false) String weekEndDate,
                                                       @RequestAttribute("userId") Long userId) {
        LocalDate date = weekEndDate == null || weekEndDate.isBlank()
                ? lastSunday(LocalDate.now())
                : LocalDate.parse(weekEndDate);
        return Result.success("快照已生成", snapshotService.runWeeklySnapshot(date, userId));
    }

    // ==================== 目标维护 ====================

    @GetMapping("/targets")
    @RequirePermission({"kpi:view"})
    @Operation(summary = "年度 KPI 目标列表")
    public Result<List<KpiTarget>> listTargets(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success(reportService.listTargets(targetYear));
    }

    @PutMapping("/targets")
    @RequirePermission({"kpi:manage"})
    @Operation(summary = "保存 KPI 目标（year+reportGroup 幂等）")
    public Result<KpiTarget> saveTarget(@RequestBody KpiTargetRequest request,
                                        @RequestAttribute("userId") Long userId) {
        return Result.success("目标已保存", reportService.saveTarget(request, userId));
    }

    // ==================== 偏差备注 ====================

    @PutMapping("/notes/{id}")
    @RequirePermission({"kpi:manage"})
    @Operation(summary = "填写偏差备注（偏差原因/是否异常/对策）")
    public Result<KpiDeviationNote> saveNote(@PathVariable Long id,
                                             @RequestBody KpiNoteRequest request,
                                             @RequestAttribute("userId") Long userId) {
        return Result.success("备注已保存", reportService.saveNote(id, request, userId));
    }

    // ==================== 预警规则 ====================

    @GetMapping("/alert-rules")
    @RequirePermission({"kpi:view"})
    @Operation(summary = "预警规则列表")
    public Result<List<KpiAlertRule>> listRules() {
        return Result.success(reportService.listRules());
    }

    @PutMapping("/alert-rules")
    @RequirePermission({"kpi:manage"})
    @Operation(summary = "保存预警规则（reportGroup 空=全局默认）")
    public Result<KpiAlertRule> saveRule(@RequestBody KpiAlertRuleRequest request,
                                         @RequestAttribute("userId") Long userId) {
        return Result.success("规则已保存", reportService.saveRule(request, userId));
    }

    // ==================== 导出 ====================

    @GetMapping("/report/export")
    @RequirePermission({"kpi:view"})
    @Operation(summary = "导出 KPI 周报 Excel（复刻 2026KPI.xlsx 结构 + 偏差备注明细）")
    public org.springframework.http.ResponseEntity<byte[]> exportReport(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        byte[] content = exportService.exportReport(targetYear);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=KPI-" + targetYear + ".xlsx")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @GetMapping("/report/summary")
    @RequirePermission({"kpi:view"})
    @Operation(summary = "文字版周报摘要（可直接粘贴汇报）")
    public Result<String> summary(@RequestParam(required = false) Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        return Result.success(exportService.buildSummary(targetYear));
    }

    private LocalDate lastSunday(LocalDate date) {
        return date.with(DayOfWeek.SUNDAY).isAfter(date)
                ? date.with(DayOfWeek.SUNDAY).minusWeeks(1)
                : date.with(DayOfWeek.SUNDAY);
    }
}
