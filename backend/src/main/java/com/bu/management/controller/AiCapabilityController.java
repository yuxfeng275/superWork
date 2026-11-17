package com.bu.management.controller;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.SysRole;
import com.bu.management.service.AiCapabilityStatsService;
import com.bu.management.service.SysRoleService;
import com.bu.management.vo.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力调用统计：工具到底有没有被调、成功率与超时率多少。
 *
 * <p>只对管理岗位开放：这是运行观测，不是业务功能，普通用户看到也处理不了。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@RestController
@RequestMapping("/api/ai-capabilities")
@RequiredArgsConstructor
public class AiCapabilityController {

    private final AiCapabilityStatsService statsService;

    private final SysRoleService sysRoleService;

    /**
     * 近 N 天能力调用统计（默认 7 天，最长 90 天）。
     *
     * @param userId 当前用户，仅管理岗位可调
     * @param days   统计窗口天数
     */
    @GetMapping("/stats")
    public Result<AiCapabilityStatsService.Report> stats(@RequestAttribute("userId") Long userId,
                                                         @RequestParam(defaultValue = "7") int days) {
        requireAdmin(userId);
        return Result.success(statsService.report(days));
    }

    /** 管理岗位校验：与知识库管理接口同一口径。 */
    private void requireAdmin(Long userId) {
        List<SysRole> roles = sysRoleService.getRolesByUserId(userId);
        boolean admin = roles != null && roles.stream()
                .map(SysRole::getCode)
                .anyMatch(PositionRoles::isAdminRole);
        if (!admin) {
            throw new RuntimeException("仅管理岗位可查看能力调用统计");
        }
    }
}
