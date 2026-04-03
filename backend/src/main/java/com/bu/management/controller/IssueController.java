package com.bu.management.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateIssueDTO;
import com.bu.management.dto.UpdateIssueDTO;
import com.bu.management.entity.Issue;
import com.bu.management.service.IssueService;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 事项控制器
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Tag(name = "事项管理", description = "事项管理接口")
@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    /**
     * 创建事项
     */
    @Operation(summary = "创建事项", description = "创建事项")
    @PostMapping
    public Result<Issue> createIssue(@RequestBody CreateIssueDTO dto) {
        Issue issue = issueService.createIssue(dto);
        return Result.success(issue);
    }

    /**
     * 更新事项
     */
    @Operation(summary = "更新事项", description = "更新事项")
    @PutMapping("/{id}")
    public Result<Issue> updateIssue(
            @Parameter(description = "事项ID") @PathVariable Long id,
            @RequestBody UpdateIssueDTO dto) {
        Issue issue = issueService.updateIssue(id, dto);
        return Result.success(issue);
    }

    /**
     * 查询事项详情
     */
    @Operation(summary = "查询事项详情", description = "根据事项ID查询详情")
    @GetMapping("/{id}")
    public Result<Issue> getIssue(@Parameter(description = "事项ID") @PathVariable Long id) {
        Issue issue = issueService.getIssueById(id);
        return Result.success(issue);
    }

    /**
     * 查询关联对象的事项列表
     */
    @Operation(summary = "查询关联对象的事项", description = "根据关联类型和关联ID查询所有事项")
    @GetMapping("/related/{relatedType}/{relatedId}")
    public Result<List<Issue>> getIssuesByRelated(
            @Parameter(description = "关联类型") @PathVariable String relatedType,
            @Parameter(description = "关联ID") @PathVariable Long relatedId) {
        List<Issue> issues = issueService.getIssuesByRelated(relatedType, relatedId);
        return Result.success(issues);
    }

    /**
     * 分页查询事项
     */
    @Operation(summary = "分页查询事项", description = "分页查询事项，支持按关联类型、关联ID、事项类型、严重程度、状态、负责人筛选")
    @GetMapping
    public Result<Page<Issue>> getIssuesPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "关联类型") @RequestParam(required = false) String relatedType,
            @Parameter(description = "关联ID") @RequestParam(required = false) Long relatedId,
            @Parameter(description = "事项类型") @RequestParam(required = false) String issueType,
            @Parameter(description = "严重程度") @RequestParam(required = false) String severity,
            @Parameter(description = "状态") @RequestParam(required = false) String status,
            @Parameter(description = "负责人ID") @RequestParam(required = false) Long assigneeId) {
        Page<Issue> result = issueService.getIssuesPage(page, size, relatedType, relatedId, issueType, severity, status, assigneeId);
        return Result.success(result);
    }

    /**
     * 删除事项
     */
    @Operation(summary = "删除事项", description = "删除事项")
    @DeleteMapping("/{id}")
    public Result<Void> deleteIssue(@Parameter(description = "事项ID") @PathVariable Long id) {
        issueService.deleteIssue(id);
        return Result.success();
    }
}
