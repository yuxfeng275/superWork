package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.vo.Result;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.service.AiModelConfigService;
import com.bu.management.service.SysRoleService;
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
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Tag(name = "周报中心", description = "BG 周报与周会纪要：事实采集、AI 生成、审阅、语雀发布、企微推送")
@RestController
@RequestMapping("/api/weekly-reports")
@RequiredArgsConstructor
public class WeeklyReportController {

    /** 当前周报生成模型信息（供编辑器展示模型接入）。 */
    public record GenerationModelInfo(boolean configured, String providerCode, String providerName,
                                      String model, String baseUrl) {}

    private final WeeklyReportService reportService;
    private final AiModelConfigService aiModelConfigService;
    private final SysRoleService sysRoleService;

    @GetMapping
    @RequirePermission({"weekly:view"})
    @Operation(summary = "获取指定周周报（无则自动创建 PENDING 记录）")
    public Result<WeeklyReportVO> get(@RequestParam(required = false) String weekStart,
                                      @RequestAttribute("userId") Long userId) {
        LocalDate monday = parseWeekStart(weekStart);
        return Result.success(toVO(reportService.getOrCreate(monday), userId, true));
    }

    @GetMapping("/facts")
    @RequirePermission({"weekly:view"})
    @Operation(summary = "本周自动采集事实（大事儿 + 财务 + 上周闭环）")
    public Result<Map<String, Object>> facts(@RequestParam(required = false) String weekStart) {
        return Result.success(reportService.collectFacts(parseWeekStart(weekStart)));
    }

    @PutMapping("/{id}/inputs")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "保存人工输入（企微智能总结 / 补充信息 / 生成提示词）")
    public Result<WeeklyReportVO> saveInputs(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             @RequestAttribute("userId") Long userId) {
        Map<String, String> payload = body == null ? Map.of() : body;
        return Result.success("已保存", toVO(reportService.saveInputs(
                id, payload.get("wecomSummary"), payload.get("manualNotes"), payload.get("generationPrompt")),
                userId, true));
    }

    @GetMapping("/generation-model")
    @RequirePermission({"weekly:view"})
    @Operation(summary = "当前周报生成使用的模型（模型管理 · 摘要用途第一条）")
    public Result<GenerationModelInfo> generationModel() {
        return Result.success(aiModelConfigService.digestModel()
                .map(digest -> new GenerationModelInfo(true, digest.providerCode(),
                        aiModelConfigService.providerDisplayName(digest.providerCode()),
                        digest.model(), digest.baseUrl()))
                .orElse(new GenerationModelInfo(false, null, null, null, null)));
    }

    @PostMapping("/{id}/generate")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "异步触发 AI 生成")
    public Result<WeeklyReportVO> generate(@PathVariable Long id,
                                           @RequestAttribute("userId") Long userId) {
        WeeklyReport report = reportService.getById(id);
        WeeklyReport triggered = reportService.generateAsync(report.getWeekStartDate());
        if (triggered == null) {
            return Result.error(409, "周报正在生成中，请稍后");
        }
        return Result.success("已开始生成", toVO(triggered, userId, false));
    }

    @PutMapping("/{id}/content")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "保存编辑内容（4 段 + 纪要）")
    public Result<WeeklyReportVO> saveContent(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, String> body,
                                              @RequestAttribute("userId") Long userId) {
        Map<String, String> payload = body == null ? Map.of() : body;
        return Result.success("已保存", toVO(reportService.saveContent(id,
                payload.get("coreWork"), payload.get("kpiSection"), payload.get("risks"),
                payload.get("nextWeekPlan"), payload.get("minutesMarkdown")), userId, true));
    }

    @PutMapping("/{id}/confirm")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "确认周报草稿")
    public Result<WeeklyReportVO> confirm(@PathVariable Long id,
                                          @RequestAttribute("userId") Long userId) {
        return Result.success("已确认", toVO(reportService.confirm(id), userId, true));
    }

    @PostMapping("/{id}/publish-yuque")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "发布周会纪要至语雀（创建文档 + 挂载 TOC）")
    public Result<WeeklyReportVO> publishYuque(@PathVariable Long id,
                                               @RequestAttribute("userId") Long userId) {
        return Result.success("已发布语雀", toVO(reportService.publishYuque(id, userId), userId, true));
    }

    @PostMapping("/{id}/publish-sheet")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "确认汇总表已回填（MANUAL 标记完成；API 模式才自动写入）")
    public Result<WeeklyReportVO> publishSheet(@PathVariable Long id,
                                               @RequestAttribute("userId") Long userId) {
        WeeklyReportVO vo = toVO(reportService.fillSheet(id), userId, true);
        return Result.success("已标记回填", vo);
    }

    @PostMapping("/{id}/push-wecom")
    @RequirePermission({"weekly:manage"})
    @Operation(summary = "推送企微提醒")
    public Result<WeeklyReportVO> pushWecom(@PathVariable Long id,
                                            @RequestAttribute("userId") Long userId) {
        return Result.success("已推送企微", toVO(reportService.pushWecom(id), userId, true));
    }

    @GetMapping("/history")
    @RequirePermission({"weekly:view"})
    @Operation(summary = "历史周报列表（最近 20 周）")
    public Result<List<WeeklyReportVO>> history(@RequestAttribute("userId") Long userId) {
        boolean canManage = canManage(userId);
        return Result.success(reportService.history(20).stream()
                .map(report -> toVO(report, canManage, false)).toList());
    }

    private boolean canManage(Long userId) {
        return userId != null
                && sysRoleService.getPermissionCodesByUserId(userId).contains("weekly:manage");
    }

    /**
     * 统一转 VO。
     * 列表/写操作不再采集事实：事实采集涉及大事儿、商机、合同多条查询，
     * 以前每个保存响应都跑一遍，慢的时候会被网关判超时，出现「内容已保存但前端报错」。
     *
     * @param withSheetInfo 是否需要汇总表目标行信息（列表页不用）
     */
    private WeeklyReportVO toVO(WeeklyReport report, Long userId, boolean withSheetInfo) {
        return toVO(report, canManage(userId), withSheetInfo);
    }

    private WeeklyReportVO toVO(WeeklyReport report, boolean canManage, boolean withSheetInfo) {
        WeeklyReportVO vo = WeeklyReportVO.from(report);
        vo.setCanManage(canManage);
        if (org.springframework.util.StringUtils.hasText(report.getGenerationProvider())) {
            vo.setGenerationProviderName(
                    aiModelConfigService.providerDisplayName(report.getGenerationProvider()));
        }
        if (withSheetInfo) {
            vo.setSheetTargetInfo(reportService.sheetTargetInfo(report));
        }
        return vo;
    }

    private LocalDate parseWeekStart(String weekStart) {
        if (weekStart == null || weekStart.isBlank()) {
            return LocalDate.now().with(DayOfWeek.MONDAY);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(weekStart);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("weekStart 格式无效，应为周一日期 YYYY-MM-DD");
        }
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("weekStart 必须是周一");
        }
        return date;
    }
}
