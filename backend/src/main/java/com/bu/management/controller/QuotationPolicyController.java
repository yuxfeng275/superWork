package com.bu.management.controller;

import com.bu.management.dto.QuotationPolicyItemRequest;
import com.bu.management.dto.QuotationPolicyRequest;
import com.bu.management.entity.QuotationPolicy;
import com.bu.management.entity.QuotationPolicyItem;
import com.bu.management.service.QuotationPolicyService;
import com.bu.management.vo.QuotationPolicyVO;
import com.bu.management.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/quotation-policies")
@RequiredArgsConstructor
public class QuotationPolicyController {
    private final QuotationPolicyService service;

    @GetMapping
    public Result<List<QuotationPolicy>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String taxMode,
            @RequestParam(required = false) String status) {
        return Result.success(service.listPolicies(type, taxMode, status));
    }

    @GetMapping("/{id}")
    public Result<QuotationPolicyVO> get(@PathVariable Long id) {
        return Result.success(service.getPolicyDetail(id));
    }

    @GetMapping("/published")
    public Result<QuotationPolicyVO> getPublished(
            @RequestParam String type,
            @RequestParam String taxMode) {
        return Result.success(service.getPublishedPolicy(type, taxMode));
    }

    @PostMapping
    public Result<QuotationPolicy> create(@RequestBody QuotationPolicyRequest request) {
        return Result.success(service.createPolicy(request));
    }

    @PutMapping("/{id}")
    public Result<QuotationPolicy> update(@PathVariable Long id, @RequestBody QuotationPolicyRequest request) {
        return Result.success(service.updatePolicy(id, request));
    }

    @PutMapping("/{id}/publish")
    public Result<QuotationPolicy> publish(@PathVariable Long id) {
        return Result.success(service.publishPolicy(id));
    }

    @PutMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        service.archivePolicy(id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deletePolicy(id);
        return Result.success();
    }

    @GetMapping("/{policyId}/items")
    public Result<List<QuotationPolicyItem>> listItems(@PathVariable Long policyId) {
        return Result.success(service.listPolicyItems(policyId));
    }

    @PostMapping("/{policyId}/items")
    public Result<Void> addItems(@PathVariable Long policyId, @RequestBody List<QuotationPolicyItemRequest> requests) {
        service.addPolicyItems(policyId, requests);
        return Result.success();
    }

    @PutMapping("/items/{itemId}")
    public Result<Void> updateItem(@PathVariable Long itemId, @RequestBody QuotationPolicyItemRequest request) {
        service.updatePolicyItem(itemId, request);
        return Result.success();
    }

    @DeleteMapping("/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long itemId) {
        service.deletePolicyItem(itemId);
        return Result.success();
    }
}