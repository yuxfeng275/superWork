package com.bu.management.controller;

import com.bu.management.dto.SalesOpportunityFollowUpRequest;
import com.bu.management.dto.SalesOpportunityRequest;
import com.bu.management.dto.SalesOpportunitySupportWorkLogRequest;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.SalesOpportunitySupportWorkLog;
import com.bu.management.service.SalesOpportunityService;
import com.bu.management.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/sales-opportunities")
@RequiredArgsConstructor
public class SalesOpportunityController {
    private final SalesOpportunityService service;
    @GetMapping public Result<List<SalesOpportunity>> list(@RequestParam(required=false) String keyword, @RequestParam(required=false) String type, @RequestParam(required=false) String status, @RequestParam(required=false) String owner, @RequestParam(required=false) String businessLine) { return Result.success(service.list(keyword, type, status, owner, businessLine)); }
    @GetMapping("/{id}") public Result<SalesOpportunity> get(@PathVariable Long id) { return Result.success(service.get(id)); }
    @GetMapping("/{id}/follow-ups") public Result<List<SalesOpportunityFollowUp>> listFollowUps(@PathVariable Long id) { return Result.success(service.listFollowUps(id)); }
    @GetMapping("/{id}/support-worklogs") public Result<List<SalesOpportunitySupportWorkLog>> listSupportWorkLogs(@PathVariable Long id) { return Result.success(service.listSupportWorkLogs(id)); }
    @PostMapping public Result<SalesOpportunity> create(@RequestBody SalesOpportunityRequest request) { return Result.success(service.create(request)); }
    @PostMapping("/{id}/follow-ups") public Result<SalesOpportunityFollowUp> createFollowUp(@PathVariable Long id, @RequestBody SalesOpportunityFollowUpRequest request) { return Result.success(service.createFollowUp(id, request)); }
    @PostMapping("/{id}/support-worklogs") public Result<SalesOpportunitySupportWorkLog> createSupportWorkLog(@PathVariable Long id, @RequestBody SalesOpportunitySupportWorkLogRequest request) { return Result.success(service.createSupportWorkLog(id, request)); }
    @PutMapping("/{id}") public Result<SalesOpportunity> update(@PathVariable Long id, @RequestBody SalesOpportunityRequest request) { return Result.success(service.update(id, request)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable Long id) { service.delete(id); return Result.success(); }
}
