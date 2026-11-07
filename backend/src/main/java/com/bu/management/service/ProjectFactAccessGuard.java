package com.bu.management.service;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.Project;
import com.bu.management.entity.SysRole;
import com.bu.management.mapper.ProjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 项目事实的读写准入（只管「能不能进这个项目」；具体哪条事实谁能看，见 {@link ProjectFactPolicy}）。
 *
 * <p>投递入口（文件 / 说明 / 聊天）不得裸奔：{@code project:view} 只说明「登录且能看项目」，
 * 具体某个项目还要求下列任一成立：
 * <ol>
 *   <li>用户角色属于 {@link PositionRoles} 的管理角色（总监 / 副总监 / 经营负责人 / 成效负责人 / BU_ADMIN）；</li>
 *   <li>{@code project.manager_id == userId}（项目负责人）；</li>
 *   <li>project_member 存在 (projectId, userId)；</li>
 *   <li>用户拥有 {@code project:manage} 权限。</li>
 * </ol>
 * 项目不存在一律 false。日志只记 projectId/userId/结果，不记事实内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFactAccessGuard {

    /** 「管理项目」权限码：有它就等于可以读写任意项目的事实。 */
    static final String PERMISSION_PROJECT_MANAGE = "project:manage";

    private final ProjectMapper projectMapper;
    private final DataPermissionService dataPermissionService;
    private final SysRoleService sysRoleService;

    /** 能否读该项目的事实。 */
    public boolean canRead(Long userId, Long projectId) {
        return allowed(userId, projectId, "read");
    }

    /** 能否往该项目写事实。 */
    public boolean canWrite(Long userId, Long projectId) {
        return allowed(userId, projectId, "write");
    }

    /** 是否该项目的管理者（项目负责人或管理角色）：PRIVATE_COMPANY 事实的可见门槛。 */
    public boolean isProjectManager(Long userId, Long projectId) {
        if (userId == null || projectId == null) {
            return false;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        return userId.equals(project.getManagerId()) || hasAdminRole(userId);
    }

    private boolean allowed(Long userId, Long projectId, String action) {
        boolean ok = false;
        if (userId != null && projectId != null) {
            Project project = projectMapper.selectById(projectId);
            ok = project != null
                    && (hasAdminRole(userId)
                        || userId.equals(project.getManagerId())
                        || dataPermissionService.isProjectMember(userId, projectId)
                        || hasPermission(userId, PERMISSION_PROJECT_MANAGE));
        }
        log.debug("项目事实访问判定: projectId={}, userId={}, action={}, allowed={}",
                projectId, userId, action, ok);
        return ok;
    }

    private boolean hasAdminRole(Long userId) {
        List<SysRole> roles = sysRoleService.getRolesByUserId(userId);
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        return roles.stream()
                .map(SysRole::getCode)
                .anyMatch(PositionRoles::isAdminRole);
    }

    private boolean hasPermission(Long userId, String code) {
        List<String> permissions = sysRoleService.getPermissionCodesByUserId(userId);
        return permissions != null && permissions.contains(code);
    }
}
