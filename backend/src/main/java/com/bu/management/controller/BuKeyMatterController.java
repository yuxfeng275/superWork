package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.annotation.RequireUsername;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.dto.BuKeyMatterWeeklyUpdateRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.BuKeyMatterWeeklyUpdate;
import com.bu.management.service.BuKeyMatterService;
import com.bu.management.vo.BuKeyMatterView;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;

@Tag(name = "大事儿管理", description = "维护BU重点事项、每周进展和周会视图")
@RestController
@RequestMapping("/api/key-matters")
@RequiredArgsConstructor
@RequirePermission({"bu:key-matter:manage"})
@RequireUsername({"admin", "yufeng"})
public class BuKeyMatterController {

    private final BuKeyMatterService service;

    @GetMapping
    @Operation(summary = "查询大事儿台账")
    public Result<List<BuKeyMatterView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long projectId) {
        return Result.success(service.list(keyword, status, priority, ownerId, projectId));
    }

    @GetMapping("/meeting")
    @Operation(summary = "查询指定周的周会视图")
    public Result<List<BuKeyMatterView>> meeting(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
        return Result.success(service.meeting(weekStartDate));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询大事儿详情")
    public Result<BuKeyMatterView> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @PostMapping
    @Operation(summary = "创建大事儿")
    public Result<BuKeyMatter> create(@RequestBody BuKeyMatterRequest request,
                                      @RequestAttribute("userId") Long userId) {
        return Result.success(service.create(request, userId));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新大事儿")
    public Result<BuKeyMatter> update(@PathVariable Long id,
                                      @RequestBody BuKeyMatterRequest request) {
        return Result.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除大事儿")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/weekly-updates/{weekStartDate}")
    @Operation(summary = "新增或更新指定周进展")
    public Result<BuKeyMatterWeeklyUpdate> upsertWeeklyUpdate(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
            @RequestBody BuKeyMatterWeeklyUpdateRequest request,
            @RequestAttribute("userId") Long userId) {
        return Result.success(service.upsertWeeklyUpdate(id, weekStartDate, request, userId));
    }

    @DeleteMapping("/{id}/weekly-updates/{weekStartDate}")
    @Operation(summary = "删除指定周进展")
    public Result<Void> deleteWeeklyUpdate(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
        service.deleteWeeklyUpdate(id, weekStartDate);
        return Result.success();
    }
}
