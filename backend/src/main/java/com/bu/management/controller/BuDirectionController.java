package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.BuDirectionRequest;
import com.bu.management.entity.BuDirection;
import com.bu.management.service.BuDashboardService;
import com.bu.management.service.BuDirectionService;
import com.bu.management.vo.BuDirectionView;
import com.bu.management.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "BU重点方向", description = "维护方向、关联项目和里程碑")
@RestController
@RequestMapping("/api/bu-directions")
@RequiredArgsConstructor
@RequirePermission({"bu:dashboard:view"})
public class BuDirectionController {

    private final BuDirectionService directionService;
    private final BuDashboardService dashboardService;

    @GetMapping
    public Result<List<BuDirectionView>> list() {
        return Result.success(dashboardService.buildDirections());
    }

    @PostMapping
    @RequirePermission({"bu:direction:manage"})
    @Operation(summary = "创建BU重点方向")
    public Result<BuDirection> create(@RequestBody BuDirectionRequest request,
                                      @RequestAttribute("userId") Long userId) {
        return Result.success(directionService.create(request, userId));
    }

    @PutMapping("/{id}")
    @RequirePermission({"bu:direction:manage"})
    @Operation(summary = "更新BU重点方向")
    public Result<BuDirection> update(@PathVariable Long id, @RequestBody BuDirectionRequest request) {
        return Result.success(directionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission({"bu:direction:manage"})
    @Operation(summary = "删除BU重点方向")
    public Result<Void> delete(@PathVariable Long id) {
        directionService.delete(id);
        return Result.success();
    }
}
