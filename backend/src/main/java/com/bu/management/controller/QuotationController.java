package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.QuotationDiscountDecisionRequest;
import com.bu.management.dto.QuotationGenerateRequest;
import com.bu.management.dto.QuotationStatusRequest;
import com.bu.management.dto.QuotationUpdateRequest;
import com.bu.management.entity.QuotationApprovalLog;
import com.bu.management.entity.QuotationBrandScope;
import com.bu.management.entity.QuotationDiscountRequest;
import com.bu.management.entity.QuotationVariant;
import com.bu.management.service.QuotationDiscountService;
import com.bu.management.service.QuotationRepriceService;
import com.bu.management.service.QuotationService;
import com.bu.management.service.QuotationWorkflowService;
import com.bu.management.vo.QuotationListVO;
import com.bu.management.vo.QuotationPricingResult;
import com.bu.management.vo.QuotationVO;
import com.bu.management.vo.Result;
import com.bu.management.util.QuotationExportHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/quotations")
@RequiredArgsConstructor
public class QuotationController {
    private final QuotationService service;
    private final QuotationWorkflowService workflowService;
    private final QuotationDiscountService discountService;
    private final QuotationRepriceService repriceService;
    private final QuotationExportHandler exportHandler;

    @GetMapping
    public Result<Page<QuotationListVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) Long opportunityId,
            @RequestParam(required = false) Boolean dueSoon,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.listQuotations(keyword, status, customerName, opportunityId, dueSoon, page, size));
    }

    @GetMapping("/{id}")
    public Result<QuotationVO> get(@PathVariable Long id) {
        return Result.success(service.getQuotationDetail(id));
    }

    @PostMapping("/generate")
    public Result<QuotationVO> generate(@RequestBody QuotationGenerateRequest request) {
        return Result.success(service.generateQuotation(request));
    }

    /** 试算：前端报价编辑器实时汇总，不落库。 */
    @PostMapping("/preview")
    public Result<QuotationPricingResult> preview(@RequestBody QuotationGenerateRequest request) {
        return Result.success(service.preview(request.getPolicyId(), request.getLineItemOverrides(),
                request.getOpportunityId()));
    }

    @PutMapping("/{id}")
    public Result<QuotationVO> update(@PathVariable Long id, @RequestBody QuotationUpdateRequest request) {
        return Result.success(service.updateQuotation(id, request));
    }

    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody QuotationStatusRequest request) {
        workflowService.transition(id, request.getStatus(), request.getOperator(), request.getComment());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteQuotation(id);
        return Result.success();
    }

    // ===== 方案 / 版本 =====

    @PostMapping("/{id}/versions")
    public Result<QuotationVO> createVersion(@PathVariable Long id) {
        return Result.success(service.createVersion(id));
    }

    @PostMapping("/{id}/variants")
    public Result<QuotationVO> createVariant(@PathVariable Long id,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String description) {
        return Result.success(service.createVariant(id, name, description));
    }

    @GetMapping("/variants")
    public Result<List<QuotationVariant>> listVariants(@RequestParam Long opportunityId) {
        return Result.success(service.listVariants(opportunityId));
    }

    @PutMapping("/{id}/preferred")
    public Result<Void> setPreferred(@PathVariable Long id) {
        service.setPreferred(id);
        return Result.success();
    }

    @PutMapping("/{id}/brand-scopes")
    public Result<Void> replaceBrandScopes(@PathVariable Long id, @RequestBody List<QuotationBrandScope> scopes) {
        service.replaceBrandScopes(id, scopes);
        return Result.success();
    }

    // ===== 审批 =====

    @GetMapping("/{id}/approvals")
    public Result<List<QuotationApprovalLog>> approvals(@PathVariable Long id) {
        return Result.success(workflowService.history(id));
    }

    @PostMapping("/{id}/discount-requests")
    public Result<QuotationDiscountRequest> requestDiscount(@PathVariable Long id,
                                                            @RequestBody QuotationDiscountDecisionRequest request) {
        return Result.success(discountService.request(id, request));
    }

    @GetMapping("/discount-requests")
    public Result<List<QuotationDiscountRequest>> listDiscountRequests(@RequestParam(required = false) String status) {
        return Result.success(discountService.list(status));
    }

    @PutMapping("/discount-requests/{requestId}")
    public Result<QuotationDiscountRequest> decideDiscount(@PathVariable Long requestId,
                                                           @RequestBody QuotationDiscountDecisionRequest request) {
        return Result.success(discountService.decide(requestId, request));
    }

    /** 存量报价单按新定价规则重算；dryRun=true 只返回会变化的报价单 ID。 */
    @PostMapping("/reprice")
    public Result<List<Long>> reprice(@RequestParam(defaultValue = "false") boolean includeSent,
                                      @RequestParam(defaultValue = "true") boolean dryRun) {
        return Result.success(repriceService.repriceAll(includeSent, dryRun));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) throws IOException {
        return exportHandler.export(id);
    }
}
