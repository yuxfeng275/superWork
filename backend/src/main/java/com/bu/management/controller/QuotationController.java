package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.QuotationGenerateRequest;
import com.bu.management.dto.QuotationStatusRequest;
import com.bu.management.dto.QuotationUpdateRequest;
import com.bu.management.service.QuotationService;
import com.bu.management.vo.QuotationListVO;
import com.bu.management.vo.QuotationVO;
import com.bu.management.vo.Result;
import com.bu.management.util.QuotationExportHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
public class QuotationController {
    private final QuotationService service;
    private final QuotationExportHandler exportHandler;

    @GetMapping
    public Result<Page<QuotationListVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listQuotations(keyword, status, customerName, page, size));
    }

    @GetMapping("/{id}")
    public Result<QuotationVO> get(@PathVariable Long id) {
        return Result.success(service.getQuotationDetail(id));
    }

    @PostMapping("/generate")
    public Result<QuotationVO> generate(@RequestBody QuotationGenerateRequest request) {
        return Result.success(service.generateQuotation(request));
    }

    @PutMapping("/{id}")
    public Result<QuotationVO> update(@PathVariable Long id, @RequestBody QuotationUpdateRequest request) {
        return Result.success(service.updateQuotation(id, request));
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody QuotationStatusRequest request) {
        service.updateStatus(id, request.getStatus());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteQuotation(id);
        return Result.success();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) throws IOException {
        return exportHandler.export(id);
    }
}