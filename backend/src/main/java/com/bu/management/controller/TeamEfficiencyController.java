package com.bu.management.controller;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.SysRole;
import com.bu.management.service.SysRoleService;
import com.bu.management.service.TeamEfficiencyService;
import com.bu.management.vo.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队效能视图（M3）：主管视角的投递率、卡点与负载。
 *
 * <p>只对管理岗位开放：这是带人员维度的管理视图，普通成员看到同事的负载没有业务意义。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@RestController
@RequestMapping("/api/team-efficiency")
@RequiredArgsConstructor
public class TeamEfficiencyController {

    private final TeamEfficiencyService teamEfficiencyService;

    private final SysRoleService sysRoleService;

    /**
     * 团队效能总览。
     *
     * @param userId 当前用户（仅管理岗位）
     * @param days   窗口天数，默认 30
     */
    @GetMapping("/overview")
    public Result<TeamEfficiencyService.Report> overview(@RequestAttribute("userId") Long userId,
                                                         @RequestParam(defaultValue = "30") int days) {
        requireAdmin(userId);
        return Result.success(teamEfficiencyService.overview(days));
    }

    private void requireAdmin(Long userId) {
        List<SysRole> roles = sysRoleService.getRolesByUserId(userId);
        boolean admin = roles != null && roles.stream()
                .map(SysRole::getCode)
                .anyMatch(PositionRoles::isAdminRole);
        if (!admin) {
            throw new RuntimeException("仅管理岗位可查看团队效能");
        }
    }
}
