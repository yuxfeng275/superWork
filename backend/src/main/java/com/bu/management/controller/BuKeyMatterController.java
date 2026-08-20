package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.dto.BuKeyMatterWeeklyUpdateRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.BuKeyMatterWeeklyUpdate;
import com.bu.management.service.BuKeyMatterAccessService;
import com.bu.management.service.BuKeyMatterService;
import com.bu.management.vo.BuKeyMatterAccessView;
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
public class BuKeyMatterController {

    private final BuKeyMatterService service;
    private final BuKeyMatterAccessService accessService;

    @GetMapping("/access")
    @RequirePermission({"bu:key-matter:view", "bu:key-matter:feedback", "bu:key-matter:manage"})
    @Operation(summary = "查询当前用户大事儿访问能力")
    public Result<BuKeyMatterAccessView> access(
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        return Result.success(accessService.resolveAccess(userId, username));
    }

    @GetMapping
    @RequirePermission({"bu:key-matter:view", "bu:key-matter:manage"})
    @Operation(summary = "查询大事儿台账")
    public Result<List<BuKeyMatterView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Long projectId,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        accessService.requireReadAccess(userId, username);
        return Result.success(service.list(keyword, status, priority, ownerId, projectId));
    }

    @GetMapping("/meeting")
    @RequirePermission({"bu:key-matter:view", "bu:key-matter:manage"})
    @Operation(summary = "查询指定周的周会视图")
    public Result<List<BuKeyMatterView>> meeting(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        accessService.requireReadAccess(userId, username);
        return Result.success(service.meeting(weekStartDate));
    }

    @GetMapping("/{id}")
    @RequirePermission({"bu:key-matter:view", "bu:key-matter:manage"})
    @Operation(summary = "查询大事儿详情")
    public Result<BuKeyMatterView> get(@PathVariable Long id,
                                       @RequestAttribute("userId") Long userId,
                                       @RequestAttribute("username") String username) {
        accessService.requireReadAccess(userId, username);
        return Result.success(service.get(id));
    }

    @PostMapping
    @RequirePermission({"bu:key-matter:manage"})
    @Operation(summary = "创建大事儿")
    public Result<BuKeyMatter> create(@RequestBody BuKeyMatterRequest request,
                                      @RequestAttribute("userId") Long userId,
                                      @RequestAttribute("username") String username) {
        accessService.requireManageAll(userId, username);
        return Result.success(service.create(request, userId));
    }

    @PutMapping("/{id}")
    @RequirePermission({"bu:key-matter:manage"})
    @Operation(summary = "更新大事儿")
    public Result<BuKeyMatter> update(@PathVariable Long id,
                                      @RequestBody BuKeyMatterRequest request,
                                      @RequestAttribute("userId") Long userId,
                                      @RequestAttribute("username") String username) {
        accessService.requireManageAll(userId, username);
        return Result.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission({"bu:key-matter:manage"})
    @Operation(summary = "删除大事儿")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestAttribute("userId") Long userId,
                               @RequestAttribute("username") String username) {
        accessService.requireManageAll(userId, username);
        service.delete(id);
        return Result.success();
    }

    @PutMapping("/{id}/weekly-updates/{weekStartDate}")
    @RequirePermission({"bu:key-matter:feedback", "bu:key-matter:manage"})
    @Operation(summary = "新增或更新指定周进展")
    public Result<BuKeyMatterWeeklyUpdate> upsertWeeklyUpdate(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
            @RequestBody BuKeyMatterWeeklyUpdateRequest request,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        return Result.success(service.upsertWeeklyUpdate(id, weekStartDate, request, userId, username));
    }

    @DeleteMapping("/{id}/weekly-updates/{weekStartDate}")
    @RequirePermission({"bu:key-matter:feedback", "bu:key-matter:manage"})
    @Operation(summary = "删除指定周进展")
    public Result<Void> deleteWeeklyUpdate(
            @PathVariable Long id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        service.deleteWeeklyUpdate(id, weekStartDate, userId, username);
        return Result.success();
    }
}
