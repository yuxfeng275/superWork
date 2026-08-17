package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.service.RequirementOverviewService;
import com.bu.management.service.YunxiaoWorkItemQueryService;
import com.bu.management.vo.Result;
import com.bu.management.vo.WorkItemOverviewItem;
import com.bu.management.vo.WorkItemOverviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "缺陷管理", description = "云效缺陷只读查询")
@RestController
@RequestMapping("/api/defects")
@RequiredArgsConstructor
@RequirePermission({"issue:list"})
public class DefectController {

    private final YunxiaoWorkItemQueryService queryService;

    @Operation(summary = "查询缺陷概览")
    @GetMapping("/overview")
    public Result<WorkItemOverviewResponse> overview(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String normalizedStatus,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        List<WorkItemOverviewItem> items = queryService.listCloudItems(
                YunxiaoWorkItemConstants.CATEGORY_BUG,
                (Long) request.getAttribute("userId"),
                (String) request.getAttribute("role"),
                projectId, assigneeId, normalizedStatus, keyword);
        return Result.success(RequirementOverviewService.page(
                items, Math.max(1, page), Math.min(200, Math.max(1, size))));
    }
}
