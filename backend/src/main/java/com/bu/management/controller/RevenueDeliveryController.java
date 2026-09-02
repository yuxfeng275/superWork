package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenuePlanBatchRequest;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.entity.RevenueDeliveryPlan;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.service.RevenueContractImportService;
import com.bu.management.service.RevenueDeliveryPlanService;
import com.bu.management.service.RevenueDeliverySummaryService;
import com.bu.management.service.RevenueOtherCostService;
import com.bu.management.vo.Result;
import com.bu.management.vo.RevenueDeliverySummaryVO;
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

import java.util.List;
import java.util.Map;

/**
 * 营收管理 - 项目交付营收（利润）：
 * 交付汇总、合同导入与待映射、预估交付计划、其他成本。
 * 金额单位一律为元（页面展示换算万）；工时单位人月。
 */
@Tag(name = "营收管理", description = "项目交付营收：汇总、合同导入、预估交付计划与其他成本")
@RestController
@RequestMapping("/api/revenue")
@RequiredArgsConstructor
public class RevenueDeliveryController {

    private final RevenueDeliverySummaryService summaryService;
    private final RevenueContractImportService contractImportService;
    private final RevenueDeliveryPlanService planService;
    private final RevenueOtherCostService otherCostService;

    @GetMapping("/delivery/summary")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "项目交付营收（利润）汇总：业务线×项目，h1/h2/ytd 三个时窗")
    public Result<RevenueDeliverySummaryVO> deliverySummary(@RequestParam int year,
                                                            @RequestParam(defaultValue = "true") boolean includeEstimate) {
        return Result.success(summaryService.summary(year, includeEstimate));
    }

    // ------------------------------------------------------------ 合同导入与待映射

    @PostMapping("/contracts/import")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "导入合同明细 Excel（本年销售/交付总额明细），明细表记录ID 去重可重复导入")
    public Result<RevenueImportResultVO> importContracts(@RequestParam("file") MultipartFile file,
                                                         @RequestAttribute("userId") Long userId) {
        return Result.success(contractImportService.importContracts(file, userId));
    }

    @GetMapping("/contracts/pending")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "待人工映射项目的合同明细清单")
    public Result<List<RevenueContractEntry>> listPendingContracts() {
        return Result.success(contractImportService.listPending());
    }

    @PostMapping("/contracts/pending/{id}/resolve")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "人工指定待映射合同明细归属项目（或业务线聚合行）")
    public Result<Void> resolvePendingContract(@PathVariable Long id,
                                               @RequestBody Map<String, Long> body,
                                               @RequestAttribute("userId") Long userId) {
        contractImportService.resolvePending(id, body.get("projectId"), body.get("businessLineId"), userId);
        return Result.success();
    }

    @GetMapping("/contracts/batches")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "合同导入批次历史")
    public Result<List<RevenueContractImportBatch>> listContractBatches() {
        return Result.success(contractImportService.listBatches());
    }

    // ------------------------------------------------------------ 预估交付计划

    @GetMapping("/delivery-plans")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "预估交付计划列表")
    public Result<List<RevenueDeliveryPlan>> listPlans(@RequestParam(required = false) Integer year,
                                                       @RequestParam(required = false) Long businessLineId,
                                                       @RequestParam(required = false) Long projectId) {
        return Result.success(planService.list(year, businessLineId, projectId));
    }

    @PostMapping("/delivery-plans")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "新增预估交付计划（单价快照自动计算预估工时成本）")
    public Result<RevenueDeliveryPlan> createPlan(@RequestBody RevenueDeliveryPlan request,
                                                  @RequestAttribute("userId") Long userId) {
        return Result.success(planService.create(request, userId));
    }

    @PostMapping("/delivery-plans/batch")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "按 项目×月份 批量新增预估交付计划")
    public Result<List<RevenueDeliveryPlan>> createPlanBatch(@RequestBody RevenuePlanBatchRequest request,
                                                             @RequestAttribute("userId") Long userId) {
        return Result.success(planService.createBatch(request, userId));
    }

    @PutMapping("/delivery-plans/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "修改预估交付计划（改人月后按单价快照重算成本）")
    public Result<RevenueDeliveryPlan> updatePlan(@PathVariable Long id,
                                                  @RequestBody RevenueDeliveryPlan request) {
        return Result.success(planService.update(id, request));
    }

    @DeleteMapping("/delivery-plans/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "删除预估交付计划")
    public Result<Void> deletePlan(@PathVariable Long id) {
        planService.delete(id);
        return Result.success();
    }

    // ------------------------------------------------------------ 其他成本

    @GetMapping("/other-costs")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "其他成本列表（协力/服务器/其他）")
    public Result<List<RevenueOtherCost>> listOtherCosts(@RequestParam(required = false) String yearMonth,
                                                         @RequestParam(required = false) Long businessLineId,
                                                         @RequestParam(required = false) Long projectId,
                                                         @RequestParam(required = false) String costType) {
        return Result.success(otherCostService.list(yearMonth, businessLineId, projectId, costType));
    }

    @PostMapping("/other-costs")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "新增其他成本")
    public Result<RevenueOtherCost> createOtherCost(@RequestBody RevenueOtherCost request,
                                                    @RequestAttribute("userId") Long userId) {
        return Result.success(otherCostService.create(request, userId));
    }

    @PutMapping("/other-costs/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "修改其他成本")
    public Result<RevenueOtherCost> updateOtherCost(@PathVariable Long id,
                                                    @RequestBody RevenueOtherCost request) {
        return Result.success(otherCostService.update(id, request));
    }

    @DeleteMapping("/other-costs/{id}")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "删除其他成本")
    public Result<Void> deleteOtherCost(@PathVariable Long id) {
        otherCostService.delete(id);
        return Result.success();
    }
}
