package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.YunxiaoWorkItemQueryService;
import com.bu.management.vo.Result;
import com.bu.management.vo.WorkItemOverviewItem;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/yunxiao/workitems")
@RequiredArgsConstructor
@RequirePermission({"requirement:list", "task:list", "issue:list"})
public class YunxiaoWorkItemController {

    private final YunxiaoWorkItemQueryService queryService;

    @GetMapping("/{id}")
    public Result<WorkItemOverviewItem> detail(@PathVariable String id, HttpServletRequest request) {
        return Result.success(queryService.getCloudItem(
                id,
                (Long) request.getAttribute("userId"),
                (String) request.getAttribute("role")));
    }
}
