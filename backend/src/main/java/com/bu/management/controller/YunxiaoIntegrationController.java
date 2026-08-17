package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.YunxiaoConfigRequest;
import com.bu.management.dto.YunxiaoExemptionRequest;
import com.bu.management.dto.YunxiaoProjectMappingRequest;
import com.bu.management.dto.YunxiaoUserMappingRequest;
import com.bu.management.entity.YunxiaoEffortExemption;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkitemLink;
import com.bu.management.service.WorklogComplianceService;
import com.bu.management.service.YunxiaoHandoffService;
import com.bu.management.service.YunxiaoIntegrationService;
import com.bu.management.vo.BuDashboardResponse;
import com.bu.management.vo.Result;
import com.bu.management.vo.YunxiaoConnectionTestResponse;
import com.bu.management.vo.YunxiaoMemberOption;
import com.bu.management.vo.YunxiaoProjectOption;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "云效集成", description = "云效映射、同步和需求交接")
@RestController
@RequestMapping("/api/yunxiao")
@RequiredArgsConstructor
@RequirePermission({"yunxiao:manage"})
public class YunxiaoIntegrationController {

    private final YunxiaoIntegrationService integrationService;
    private final YunxiaoHandoffService handoffService;
    private final WorklogComplianceService complianceService;

    @GetMapping("/status")
    public Result<BuDashboardResponse.IntegrationStatus> getStatus() {
        return Result.success(integrationService.getStatus());
    }

    @GetMapping("/analysis")
    public Result<Map<String, Object>> analysis() {
        return Result.success(integrationService.analysis());
    }

    @PutMapping("/config")
    public Result<BuDashboardResponse.IntegrationStatus> saveConfig(
            @Valid @RequestBody YunxiaoConfigRequest request,
            @RequestAttribute("userId") Long userId) {
        return Result.success("云效配置已保存", integrationService.saveConfig(request, userId));
    }

    @PostMapping("/connection-test")
    public Result<YunxiaoConnectionTestResponse> testConnection() {
        return Result.success(integrationService.testConnection());
    }

    @GetMapping("/project-mappings")
    public Result<List<YunxiaoProjectMapping>> listProjectMappings() {
        return Result.success(integrationService.listProjectMappings());
    }

    @GetMapping("/projects")
    public Result<List<YunxiaoProjectOption>> listProjects() {
        return Result.success(integrationService.listProjects());
    }

    @GetMapping("/members")
    public Result<List<YunxiaoMemberOption>> listMembers() {
        return Result.success(integrationService.listMembers());
    }

    @PostMapping("/project-mappings")
    public Result<YunxiaoProjectMapping> saveProjectMapping(
            @RequestBody YunxiaoProjectMappingRequest request) {
        return Result.success(integrationService.saveProjectMapping(request));
    }

    @DeleteMapping("/project-mappings/{id}")
    public Result<Void> deleteProjectMapping(@PathVariable Long id) {
        integrationService.deleteProjectMapping(id);
        return Result.success();
    }

    @GetMapping("/user-mappings")
    public Result<List<YunxiaoUserMapping>> listUserMappings() {
        return Result.success(integrationService.listUserMappings());
    }

    @PostMapping("/user-mappings")
    public Result<YunxiaoUserMapping> saveUserMapping(@RequestBody YunxiaoUserMappingRequest request) {
        return Result.success(integrationService.saveUserMapping(request));
    }

    @DeleteMapping("/user-mappings/{id}")
    public Result<Void> deleteUserMapping(@PathVariable Long id) {
        integrationService.deleteUserMapping(id);
        return Result.success();
    }

    @PostMapping("/sync")
    public Result<List<String>> sync() {
        return Result.success(integrationService.syncAll());
    }

    @PostMapping("/sync/async")
    public Result<Map<String, Object>> startAsyncSync() {
        return Result.success(integrationService.startAsyncSync());
    }

    @GetMapping("/sync/status")
    public Result<Map<String, Object>> getAsyncSyncStatus() {
        return Result.success(integrationService.getAsyncSyncStatus());
    }

    @GetMapping("/requirements/{requirementId}/link")
    @RequirePermission({"requirement:list", "yunxiao:manage"})
    public Result<YunxiaoWorkitemLink> getRequirementLink(@PathVariable Long requirementId) {
        return Result.success(handoffService.getLink(requirementId));
    }

    @PostMapping("/requirements/{requirementId}/retry")
    public Result<YunxiaoWorkitemLink> retryRequirement(@PathVariable Long requirementId) {
        return Result.success(handoffService.retry(requirementId));
    }

    @PostMapping("/requirements/{requirementId}/bind")
    public Result<YunxiaoWorkitemLink> bindRequirement(
            @PathVariable Long requirementId,
            @RequestBody Map<String, String> body) {
        return Result.success(handoffService.bind(
                requirementId,
                body.get("workitemId"),
                body.get("serialNumber")));
    }

    @PostMapping("/worklog-exemptions")
    public Result<YunxiaoEffortExemption> saveExemption(
            @RequestBody YunxiaoExemptionRequest request,
            @RequestAttribute("userId") Long userId) {
        return Result.success(complianceService.saveExemption(request, userId));
    }
}
