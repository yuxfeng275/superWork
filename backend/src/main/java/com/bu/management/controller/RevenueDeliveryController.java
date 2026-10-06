package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueContractMappingVO;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.entity.RevenueOtherCost;
import com.bu.management.service.RevenueContractImportService;
import com.bu.management.service.RevenueOtherCostService;
import com.bu.management.vo.Result;
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
 * 营收管理 - 合同明细与成本维护：
 * 合同导入与待映射归属、其他成本、按月待交付与销售确认。
 * 金额单位一律为元（页面展示换算万）；工时单位人月。
 */
@Tag(name = "营收管理", description = "营收管理：合同导入与待映射、其他成本、按月待交付确认")
@RestController
@RequestMapping("/api/revenue")
@RequiredArgsConstructor
public class RevenueDeliveryController {

    private final RevenueContractImportService contractImportService;
    private final RevenueOtherCostService otherCostService;
    private final com.bu.management.sync.SyncOrchestrator syncOrchestrator;

    // ------------------------------------------------------------ 合同导入与待映射

    @PostMapping("/contracts/import")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "导入合同明细 Excel（本年销售/交付总额明细，兜底补录；自动同步走 /api/sync）")
    public Result<RevenueImportResultVO> importContracts(@RequestParam("file") MultipartFile file,
                                                         @RequestAttribute("userId") Long userId) {
        RevenueImportResultVO result = contractImportService.importContracts(file, userId);
        syncOrchestrator.recordFallbackImport("contract", String.valueOf(java.time.LocalDate.now().getYear()),
                result.getTotalCount(), result.getSuccessCount(), result.getPendingCount(),
                "Excel 兜底导入合同明细（批次 #" + result.getBatchId() + "）", userId);
        return Result.success(result);
    }

    @GetMapping("/contracts/pending")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "待人工映射项目的合同明细清单")
    public Result<List<RevenueContractEntry>> listPendingContracts() {
        return Result.success(contractImportService.listPending());
    }

    @GetMapping("/contracts/mapped")
    @RequirePermission({"revenue:view"})
    @Operation(summary = "已映射合同明细清单")
    public Result<List<RevenueContractMappingVO>> listMappedContracts(@RequestParam(required = false) Integer year) {
        return Result.success(contractImportService.listMapped(year));
    }

    @PutMapping("/contracts/{id}/mapping")
    @RequirePermission({"revenue:manage"})
    @Operation(summary = "调整合同业务线及项目归属")
    public Result<RevenueContractMappingVO> updateContractMapping(@PathVariable Long id,
                                                                    @RequestBody Map<String, Long> body) {
        return Result.success(contractImportService.updateMapping(id, body.get("businessLineId"), body.get("projectId")));
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
