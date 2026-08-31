package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.entity.RevenueEstimateEntry;
import com.bu.management.entity.RevenueImportBatch;
import com.bu.management.entity.RevenueMonthClose;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.service.RevenueAdminService;
import com.bu.management.service.RevenueImportService;
import com.bu.management.service.RevenueMatrixService;
import com.bu.management.service.RevenueMonthService;
import com.bu.management.vo.Result;
import com.bu.management.vo.RevenueMatrixVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "营收管理", description = "工时与成本矩阵、按月导入、预估明细和月结")
@RestController
@RequestMapping("/api/revenue")
@RequiredArgsConstructor
public class RevenueController {

    private final RevenueMatrixService matrixService;
    private final RevenueImportService importService;
    private final RevenueAdminService adminService;
    private final RevenueMonthService monthService;

    @GetMapping("/matrix")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "年度工时成本矩阵")
    public Result<RevenueMatrixVO> matrix(@RequestParam int year) {
        return Result.success(matrixService.getMatrix(year));
    }

    @GetMapping("/cell-detail")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "单元格下钻：完结月返回实际明细，未完结月返回预估明细")
    public Result<Map<String, Object>> cellDetail(@RequestParam String yearMonth,
                                                  @RequestParam Long businessLineId,
                                                  @RequestParam String rowKey) {
        return Result.success(matrixService.cellDetail(yearMonth, businessLineId, rowKey));
    }

    @PostMapping("/import/worklog")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "按月导入工时明细 Excel")
    public Result<RevenueImportResultVO> importWorklog(@RequestParam("file") MultipartFile file,
                                                       @RequestParam String yearMonth,
                                                       @RequestAttribute("userId") Long userId) {
        return Result.success(importService.importWorklog(file, yearMonth, userId));
    }

    @PostMapping("/import/cost")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "导入成本分析 Excel（月份取自文件）")
    public Result<RevenueImportResultVO> importCost(@RequestParam("file") MultipartFile file,
                                                    @RequestAttribute("userId") Long userId) {
        return Result.success(importService.importCost(file, userId));
    }

    @GetMapping("/imports")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "导入批次历史")
    public Result<List<RevenueImportBatch>> listBatches(@RequestParam(required = false) String importType) {
        return Result.success(importService.listBatches(importType));
    }

    @GetMapping("/months/closed")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "已完结月份列表")
    public Result<List<RevenueMonthClose>> listClosedMonths() {
        return Result.success(monthService.list());
    }

    @PostMapping("/months/{yearMonth}/close")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "标记月份完结")
    public Result<Void> closeMonth(@PathVariable String yearMonth,
                                   @RequestAttribute("userId") Long userId) {
        monthService.close(yearMonth, userId);
        return Result.success();
    }

    @PostMapping("/months/{yearMonth}/reopen")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "取消月份完结")
    public Result<Void> reopenMonth(@PathVariable String yearMonth) {
        monthService.reopen(yearMonth);
        return Result.success();
    }

    @GetMapping("/estimates")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "预估明细列表")
    public Result<List<RevenueEstimateEntry>> listEstimates(@RequestParam(required = false) String yearMonth) {
        return Result.success(adminService.listEstimates(yearMonth));
    }

    @PostMapping("/estimates")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "新增预估明细")
    public Result<RevenueEstimateEntry> createEstimate(@RequestBody RevenueEstimateEntry request,
                                                       @RequestAttribute("userId") Long userId) {
        return Result.success(adminService.createEstimate(request, userId));
    }

    @PutMapping("/estimates/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "更新预估明细")
    public Result<RevenueEstimateEntry> updateEstimate(@PathVariable Long id,
                                                       @RequestBody RevenueEstimateEntry request) {
        return Result.success(adminService.updateEstimate(id, request));
    }

    @DeleteMapping("/estimates/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "删除预估明细")
    public Result<Void> deleteEstimate(@PathVariable Long id) {
        adminService.deleteEstimate(id);
        return Result.success();
    }

    @GetMapping("/estimates/unit-price")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "项目历史完结人均成本")
    public Result<Map<String, BigDecimal>> unitPrice(@RequestParam Long projectId) {
        return Result.success(Map.of("unitPrice", adminService.historicalUnitPrice(projectId) == null
                ? BigDecimal.ZERO : adminService.historicalUnitPrice(projectId)));
    }

    @GetMapping("/pending")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "待映射明细清单")
    public Result<Map<String, Object>> listPending() {
        return Result.success(adminService.listPending());
    }

    @PostMapping("/pending/{type}/{id}/resolve")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "人工指定待映射明细归属")
    public Result<Void> resolvePending(@PathVariable String type, @PathVariable Long id,
                                       @RequestBody Map<String, Long> body) {
        adminService.resolvePending(type, id, body.get("businessLineId"), body.get("projectId"));
        return Result.success();
    }

    @GetMapping("/sales-projects")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "销售项目注册表")
    public Result<List<Map<String, Object>>> listSalesProjects() {
        return Result.success(adminService.listSalesProjects());
    }

    @PutMapping("/sales-projects/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "销售项目关联商机")
    public Result<RevenueSalesProject> bindOpportunity(@PathVariable Long id,
                                                       @RequestBody Map<String, Long> body) {
        return Result.success(adminService.bindOpportunity(id, body.get("opportunityId")));
    }

    @GetMapping("/opportunity-options")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "可关联商机列表")
    public Result<List<SalesOpportunity>> listOpportunityOptions() {
        return Result.success(adminService.listOpportunityOptions());
    }
}
