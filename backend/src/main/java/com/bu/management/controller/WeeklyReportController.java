package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.vo.Result;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.service.WeeklyReportService;
import com.bu.management.vo.WeeklyReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "周报中心", description = "BG 周报与周会纪要：事实采集、AI 生成、审阅、语雀发布、企微推送")
@RestController
@RequestMapping("/api/weekly-reports")
@RequiredArgsConstructor
public class WeeklyReportController {

    private final WeeklyReportService reportService;

    @GetMapping
    @RequirePermission({"weekly:view"})
    @Operation(summary = "获取指定周周报（无则自动创建 PENDING 记录）")
    public Result<WeeklyReportVO> get(@RequestParam(required = false) String weekStart) {
        LocalDate monday = parseWeekStart(weekStart);
        return Result.success(toVO(reportService.getOrCreate(monday)));
    }

    @GetMapping("/facts")
    @RequirePermission({"weekly:view"})
    @Operation(summary = "本周自动采集事实（大事儿 + 财务 + 上周闭环）")
    public Result<Map<String, Object>> facts(@RequestParam(required = false) String weekStart) {
        return Result.success(reportService.collectFacts(parseWeekStart(weekStart)));
    }

    @PutMapping("/{id}/inputs")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "保存人工输入（企微智能总结 / 补充信息）")
    public Result<WeeklyReportVO> saveInputs(@PathVariable Long id,
                                             @RequestBody Map<String, String> body) {
        return Result.success("已保存", toVO(reportService.saveInputs(
                id, body.get("wecomSummary"), body.get("manualNotes"))));
    }

    @PostMapping("/{id}/generate")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "异步触发 AI 生成")
    public Result<WeeklyReportVO> generate(@PathVariable Long id) {
        WeeklyReport report = reportService.getById(id);
        WeeklyReport triggered = reportService.generateAsync(report.getWeekStartDate());
        if (triggered == null) {
            return Result.error(409, "周报正在生成中，请稍后");
        }
        return Result.success("已开始生成", toVO(triggered));
    }

    @PutMapping("/{id}/content")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "保存编辑内容（4 段 + 纪要）")
    public Result<WeeklyReportVO> saveContent(@PathVariable Long id,
                                              @RequestBody Map<String, String> body) {
        return Result.success("已保存", toVO(reportService.saveContent(id,
                body.get("coreWork"), body.get("kpiSection"), body.get("risks"),
                body.get("nextWeekPlan"), body.get("minutesMarkdown"))));
    }

    @PutMapping("/{id}/confirm")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "确认周报草稿")
    public Result<WeeklyReportVO> confirm(@PathVariable Long id) {
        return Result.success("已确认", toVO(reportService.confirm(id)));
    }

    @PostMapping("/{id}/publish-yuque")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "发布周会纪要至语雀（创建文档 + 挂载 TOC）")
    public Result<WeeklyReportVO> publishYuque(@PathVariable Long id,
                                               @RequestAttribute("userId") Long userId) {
        return Result.success("已发布语雀", toVO(reportService.publishYuque(id, userId)));
    }

    @PostMapping("/{id}/publish-sheet")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "标记汇总表已人工回填，并返回目标行信息")
    public Result<WeeklyReportVO> publishSheet(@PathVariable Long id) {
        WeeklyReportVO vo = toVO(reportService.markSheetSynced(id));
        return Result.success("已标记回填", vo);
    }

    @PostMapping("/{id}/push-wecom")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "推送企微提醒")
    public Result<WeeklyReportVO> pushWecom(@PathVariable Long id) {
        return Result.success("已推送企微", toVO(reportService.pushWecom(id)));
    }

    @GetMapping("/history")
    @RequirePermission({"weekly:view"})
    @Operation(summary = "历史周报列表（最近 20 周）")
    public Result<List<WeeklyReportVO>> history() {
        return Result.success(reportService.history(20).stream()
                .map(this::toVO).toList());
    }

    private WeeklyReportVO toVO(WeeklyReport report) {
        WeeklyReportVO vo = WeeklyReportVO.from(report);
        vo.setSheetTargetInfo(reportService.sheetTargetInfo(report.getId()));
        try {
            Map<String, Object> facts = reportService.collectFacts(report.getWeekStartDate());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matters = (List<Map<String, Object>>) facts.get("keyMatters");
            vo.setFactsPreview(matters == null ? List.of() : matters.stream().limit(5)
                    .<Map<String, Object>>map(m -> Map.of(
                            "title", String.valueOf(m.getOrDefault("title", "")),
                            "status", String.valueOf(m.getOrDefault("status", "")))).toList());
        } catch (Exception e) {
            vo.setFactsPreview(List.of());
        }
        return vo;
    }

    private LocalDate parseWeekStart(String weekStart) {
        if (weekStart == null || weekStart.isBlank()) {
            return LocalDate.now().with(DayOfWeek.MONDAY);
        }
        LocalDate date = LocalDate.parse(weekStart);
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 必须是周一");
        }
        return date;
    }
}
