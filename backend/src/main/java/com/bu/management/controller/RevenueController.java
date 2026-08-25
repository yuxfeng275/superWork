package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueManualEntryDTO;
import com.bu.management.dto.RevenueSummaryVO;
import com.bu.management.entity.RevenueProjectMapping;
import com.bu.management.service.RevenueService;
import com.bu.management.vo.Result;
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

@RestController
@RequestMapping("/api/revenue")
@RequiredArgsConstructor
public class RevenueController {
    private final RevenueService revenueService;

    @GetMapping("/summary")
    @RequirePermission({"revenue:view"})
    public Result<RevenueSummaryVO> getSummary(@RequestParam int year) {
        return Result.success(revenueService.getSummary(year));
    }

    @PostMapping("/import/cost")
    @RequirePermission({"revenue:manage"})
    public Result<RevenueImportResultVO> importCost(@RequestParam("file") MultipartFile file) {
        return Result.success(revenueService.importCostExcel(file));
    }

    @PostMapping("/import/income")
    @RequirePermission({"revenue:manage"})
    public Result<RevenueImportResultVO> importIncome(@RequestParam("file") MultipartFile file) {
        return Result.success(revenueService.importIncomeExcel(file));
    }

    @GetMapping("/mappings")
    @RequirePermission({"revenue:view"})
    public Result<List<RevenueProjectMapping>> listMappings(
            @RequestParam(required = false) String sourceType) {
        return Result.success(revenueService.listMappings(sourceType));
    }

    @PutMapping("/mappings/{id}")
    @RequirePermission({"revenue:manage"})
    public Result<RevenueProjectMapping> updateMapping(
            @PathVariable Long id, @RequestBody RevenueProjectMapping request) {
        return Result.success(revenueService.updateMapping(id, request));
    }

    @GetMapping("/manual")
    @RequirePermission({"revenue:view"})
    public Result<List<RevenueManualEntryDTO>> listManualEntries(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String month) {
        return Result.success(revenueService.listManualEntries(year, month));
    }

    @PostMapping("/manual")
    @RequirePermission({"revenue:manage"})
    public Result<RevenueManualEntryDTO> createManualEntry(
            @RequestBody RevenueManualEntryDTO request,
            @RequestAttribute("userId") Long userId) {
        return Result.success(revenueService.createManualEntry(request, userId));
    }

    @PutMapping("/manual/{id}")
    @RequirePermission({"revenue:manage"})
    public Result<RevenueManualEntryDTO> updateManualEntry(
            @PathVariable Long id, @RequestBody RevenueManualEntryDTO request) {
        return Result.success(revenueService.updateManualEntry(id, request));
    }

    @DeleteMapping("/manual/{id}")
    @RequirePermission({"revenue:manage"})
    public Result<Void> deleteManualEntry(@PathVariable Long id) {
        revenueService.deleteManualEntry(id);
        return Result.success();
    }
}
