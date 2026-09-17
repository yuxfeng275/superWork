package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.entity.EmailMessage;
import com.bu.management.dto.EmailAccountRequest;
import com.bu.management.dto.EmailWeComMappingRequest;
import com.bu.management.service.EmailAccountService;
import com.bu.management.service.EmailDigestService;
import com.bu.management.service.EmailQueryService;
import com.bu.management.service.EmailInterpretationService;
import com.bu.management.service.EmailProjectGroupingService;
import com.bu.management.service.EmailSyncService;
import java.util.Map;

import com.bu.management.vo.EmailAccountStatus;
import com.bu.management.vo.EmailConnectionTestResponse;
import com.bu.management.vo.EmailDigestResponse;
import com.bu.management.vo.EmailMessageDetail;
import com.bu.management.vo.EmailMessageListItem;
import com.bu.management.vo.EmailInterpretationView;
import com.bu.management.vo.EmailGroupingJobStatus;
import com.bu.management.vo.EmailProjectGroupView;
import com.bu.management.vo.EmailSenderCompanyGroupView;
import com.bu.management.vo.EmailSyncStatus;
import com.bu.management.vo.EmailWeComMappingStatus;
import com.bu.management.vo.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
@RequirePermission({"email:view"})
public class EmailController {
    private final EmailAccountService accountService;
    private final EmailSyncService syncService;
    private final EmailQueryService queryService;
    private final EmailDigestService digestService;
    private final EmailInterpretationService interpretationService;
    private final EmailProjectGroupingService groupingService;

    @GetMapping("/account")
    public Result<EmailAccountStatus> account(@RequestAttribute("userId") Long userId) {
        return Result.success(accountService.getStatus(userId));
    }

    @PutMapping("/account")
    @RequirePermission({"email:manage"})
    public Result<EmailAccountStatus> save(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody EmailAccountRequest request) {
        return Result.success(accountService.save(userId, request));
    }

    @PostMapping("/account/test")
    @RequirePermission({"email:manage"})
    public Result<EmailConnectionTestResponse> test(@RequestAttribute("userId") Long userId) {
        return Result.success(accountService.test(userId));
    }

    @DeleteMapping("/account")
    @RequirePermission({"email:manage"})
    public Result<Void> delete(@RequestAttribute("userId") Long userId) {
        accountService.delete(userId);
        return Result.success();
    }

    @PostMapping("/sync")
    @RequirePermission({"email:sync"})
    public Result<EmailSyncStatus> sync(@RequestAttribute("userId") Long userId) {
        return Result.success(syncService.startAsync(userId));
    }

    @GetMapping("/sync/status")
    public Result<EmailSyncStatus> syncStatus(@RequestAttribute("userId") Long userId) {
        return Result.success(syncService.status(userId));
    }

    @GetMapping("/messages")
    public Result<Page<EmailMessageListItem>> messages(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "false") boolean ungrouped,
            @RequestParam(required = false) String senderDomain) {
        return Result.success(queryService.list(userId, page, size, date, keyword,
                projectId, ungrouped, senderDomain));
    }

    @GetMapping("/project-groups")
    public Result<java.util.List<EmailProjectGroupView>> projectGroups(
            @RequestAttribute("userId") Long userId) {
        return Result.success(groupingService.groups(userId));
    }

    @GetMapping("/sender-company-groups")
    public Result<java.util.List<EmailSenderCompanyGroupView>> senderCompanyGroups(
            @RequestAttribute("userId") Long userId) {
        return Result.success(groupingService.senderCompanies(userId));
    }

    @PostMapping("/grouping")
    @RequirePermission({"email:sync"})
    public Result<EmailGroupingJobStatus> startGrouping(
            @RequestAttribute("userId") Long userId,
            @RequestParam(defaultValue = "false") boolean regroupAll) {
        return Result.success(groupingService.startAsync(userId, regroupAll));
    }

    @GetMapping("/grouping/status")
    public Result<EmailGroupingJobStatus> groupingStatus(
            @RequestAttribute("userId") Long userId) {
        return Result.success(groupingService.status(userId));
    }

    @PutMapping("/messages/{id}/project")
    @RequirePermission({"email:sync"})
    public Result<EmailMessage> assignProject(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id,
            @RequestBody Map<String, Long> body) {
        return Result.success(groupingService.assignManually(userId, id, body.get("projectId")));
    }

    @GetMapping("/messages/{id}")
    public Result<EmailMessageDetail> message(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(queryService.detail(userId, id));
    }

    @GetMapping("/messages/{id}/interpretation")
    public Result<EmailInterpretationView> interpretation(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(interpretationService.get(userId, id));
    }

    @PostMapping("/messages/{id}/interpretation")
    @RequirePermission({"email:sync"})
    public Result<EmailInterpretationView> generateInterpretation(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(interpretationService.generate(userId, id));
    }

    @GetMapping("/digests")
    public Result<EmailDigestResponse> digest(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(digestService.getResponse(userId, date));
    }

    @PostMapping("/digests/{date}/regenerate")
    @RequirePermission({"email:sync"})
    public Result<EmailDigestResponse> regenerate(
            @RequestAttribute("userId") Long userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(digestService.startRegeneration(userId, date));
    }

    @PostMapping("/digests/{id}/push/retry")
    @RequirePermission({"email:sync"})
    public Result<EmailDigestResponse> retryPush(
            @RequestAttribute("userId") Long userId,
            @PathVariable Long id) {
        return Result.success(digestService.retryPushResponse(userId, id));
    }

    @GetMapping("/wecom-mapping")
    public Result<EmailWeComMappingStatus> mapping(@RequestAttribute("userId") Long userId) {
        return Result.success(digestService.getMappingStatus(userId));
    }

    @PutMapping("/wecom-mapping")
    @RequirePermission({"email:manage"})
    public Result<EmailWeComMappingStatus> saveMapping(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody EmailWeComMappingRequest request) {
        return Result.success(digestService.saveMappingStatus(
                userId, request.getWeComUserId(), !Boolean.FALSE.equals(request.getEnabled())));
    }


}
