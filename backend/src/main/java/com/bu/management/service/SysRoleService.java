package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.SysPermission;
import com.bu.management.entity.SysMenu;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.SysRoleMenu;
import com.bu.management.entity.SysRolePermission;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysMenuMapper;
import com.bu.management.mapper.SysRoleMenuMapper;
import com.bu.management.mapper.SysPermissionMapper;
import com.bu.management.mapper.SysRoleMapper;
import com.bu.management.mapper.SysRolePermissionMapper;
import com.bu.management.mapper.SysUserRoleMapper;
import com.bu.management.vo.RoleAuthorizationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysRoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private static final Set<String> MANAGEMENT_ROLE_CODES = Set.of(
            PositionRoles.DIRECTOR,
            PositionRoles.DEPUTY_DIRECTOR,
            PositionRoles.BUSINESS_OWNER,
            PositionRoles.EFFECTIVENESS_OWNER
    );

    private static final Set<String> EXECUTION_MENU_PATHS = Set.of(
            "/home",
            "/requirements",
            "/tasks",
            "/base",
            "/business-lines",
            "/projects",
            "/customers",
            "/statistics"
    );

    private static final Map<String, Set<String>> EXECUTION_PERMISSION_CODES = Map.ofEntries(
            Map.entry(PositionRoles.SOLUTION_MANAGER, Set.of(
                    "requirement:list", "requirement:create", "requirement:edit", "requirement:delete",
                    "task:list", "task:create", "task:edit", "task:assign",
                    "issue:list", "issue:create", "issue:edit", "issue:delete",
                    "statistics:view", "org:view", "project:view", "customer-contact:view"
            )),
            Map.entry(PositionRoles.TECH_ARCHITECT, Set.of(
                    "requirement:list", "requirement:edit",
                    "task:list", "task:create", "task:edit", "task:assign",
                    "issue:list", "issue:create", "issue:edit",
                    "statistics:view", "org:view", "project:view", "customer-contact:view"
            )),
            Map.entry(PositionRoles.FULL_STACK_ENGINEER, Set.of(
                    "requirement:list",
                    "task:list", "task:create", "task:edit",
                    "issue:list", "issue:create", "issue:edit",
                    "org:view", "project:view"
            )),
            Map.entry(PositionRoles.QUALITY_ENGINEER, Set.of(
                    "requirement:list",
                    "task:list",
                    "issue:list", "issue:create", "issue:edit", "issue:delete",
                    "org:view", "project:view", "customer-contact:view"
            )),
            Map.entry(PositionRoles.AI_OPERATIONS_ENGINEER, Set.of(
                    "requirement:list", "requirement:create", "requirement:edit",
                    "task:list",
                    "issue:list", "issue:create", "issue:edit",
                    "statistics:view", "org:view", "project:view", "customer-contact:view"
            )),
            Map.entry(PositionRoles.AI_CUSTOMER_SERVICE, Set.of(
                    "requirement:list",
                    "task:list",
                    "issue:list", "issue:create", "issue:edit",
                    "org:view", "customer-contact:view"
            )),
            Map.entry(PositionRoles.EXPERIENCE_CONTENT_DESIGNER, Set.of(
                    "requirement:list",
                    "task:list", "task:create", "task:edit",
                    "issue:list",
                    "org:view", "project:view", "customer-contact:view"
            ))
    );

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Transactional(rollbackFor = Exception.class)
    public List<SysRole> getAllRoles() {
        ensureDefaultRoles();
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getStatus, 1);
        return list(wrapper);
    }

    public List<SysRole> getRolesByUserId(Long userId) {
        LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserRole::getUserId, userId);
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(wrapper);

        if (userRoles.isEmpty()) {
            return List.of();
        }

        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).toList();
        LambdaQueryWrapper<SysRole> roleWrapper = new LambdaQueryWrapper<>();
        roleWrapper.in(SysRole::getId, roleIds)
                .eq(SysRole::getStatus, 1);
        return list(roleWrapper);
    }

    public List<SysPermission> getPermissionsByRoleId(Long roleId) {
        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRoleId, roleId);
        List<SysRolePermission> rolePermissions = sysRolePermissionMapper.selectList(wrapper);

        if (rolePermissions.isEmpty()) {
            return List.of();
        }

        List<Long> permissionIds = rolePermissions.stream().map(SysRolePermission::getPermissionId).toList();
        LambdaQueryWrapper<SysPermission> permWrapper = new LambdaQueryWrapper<>();
        permWrapper.in(SysPermission::getId, permissionIds);
        return sysPermissionMapper.selectList(permWrapper);
    }

    public RoleAuthorizationVO getAuthorizationByRoleId(Long roleId) {
        LambdaQueryWrapper<SysRoleMenu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.eq(SysRoleMenu::getRoleId, roleId);
        List<Long> menuIds = sysRoleMenuMapper.selectList(menuWrapper)
                .stream()
                .map(SysRoleMenu::getMenuId)
                .toList();

        LambdaQueryWrapper<SysRolePermission> permissionWrapper = new LambdaQueryWrapper<>();
        permissionWrapper.eq(SysRolePermission::getRoleId, roleId);
        List<Long> permissionIds = sysRolePermissionMapper.selectList(permissionWrapper)
                .stream()
                .map(SysRolePermission::getPermissionId)
                .toList();

        SysRole role = getById(roleId);

        RoleAuthorizationVO authorization = new RoleAuthorizationVO();
        authorization.setMenuIds(menuIds);
        authorization.setPermissionIds(permissionIds);
        if (role != null) {
            authorization.setDataScope(role.getDataScope());
            authorization.setDataScopeValue(role.getDataScopeValue());
        }
        return authorization;
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRoleId, roleId);
        sysRolePermissionMapper.delete(wrapper);

        for (Long permissionId : safeList(permissionIds)) {
            SysRolePermission rolePermission = new SysRolePermission();
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(permissionId);
            sysRolePermissionMapper.insert(rolePermission);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignAuthorizationToRole(Long roleId, List<Long> menuIds, List<Long> permissionIds, String dataScope, String dataScopeValue) {
        LambdaQueryWrapper<SysRoleMenu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.eq(SysRoleMenu::getRoleId, roleId);
        sysRoleMenuMapper.delete(menuWrapper);

        for (Long menuId : safeList(menuIds)) {
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            sysRoleMenuMapper.insert(roleMenu);
        }

        LambdaQueryWrapper<SysRolePermission> permissionWrapper = new LambdaQueryWrapper<>();
        permissionWrapper.eq(SysRolePermission::getRoleId, roleId);
        sysRolePermissionMapper.delete(permissionWrapper);

        for (Long permissionId : safeList(permissionIds)) {
            SysRolePermission rolePermission = new SysRolePermission();
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(permissionId);
            sysRolePermissionMapper.insert(rolePermission);
        }

        // Update data scope on the role
        SysRole role = getById(roleId);
        if (role != null) {
            role.setDataScope(dataScope);
            role.setDataScopeValue(dataScopeValue);
            updateById(role);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void createRole(String code, String name, String description, Integer status) {
        PositionRoles.RolePreset preset = PositionRoles.getDefaultRole(code);
        if (preset == null) {
            throw new IllegalArgumentException("只能使用系统默认岗位角色");
        }

        LocalDateTime now = LocalDateTime.now();
        SysRole existingRole = getRoleByCode(code);
        if (existingRole != null) {
            existingRole.setName(preset.name());
            existingRole.setDescription(preset.description());
            existingRole.setStatus(status != null ? status : 1);
            if (!StringUtils.hasText(existingRole.getDataScope())) {
                existingRole.setDataScope(preset.dataScope());
            }
            if (existingRole.getCreatedAt() == null) {
                existingRole.setCreatedAt(now);
            }
            existingRole.setUpdatedAt(now);
            updateById(existingRole);
            return;
        }

        SysRole role = new SysRole();
        role.setCode(preset.code());
        role.setName(preset.name());
        role.setDescription(preset.description());
        role.setStatus(status != null ? status : 1);
        role.setDataScope(preset.dataScope());
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        save(role);
    }

    public void updateRole(Long id, String name, String description, Integer status) {
        SysRole role = getById(id);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        PositionRoles.RolePreset preset = PositionRoles.getDefaultRole(role.getCode());
        if (preset != null) {
            role.setName(preset.name());
            role.setDescription(StringUtils.hasText(description) ? description : preset.description());
            if (!StringUtils.hasText(role.getDataScope())) {
                role.setDataScope(preset.dataScope());
            }
        } else {
            role.setName(name);
            role.setDescription(description);
        }
        if (status != null) {
            role.setStatus(status);
        }
        updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        SysRole role = getById(id);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        if (PositionRoles.isDefaultRole(role.getCode())) {
            throw new IllegalArgumentException("系统默认角色不能删除");
        }

        // 删除角色-菜单关联
        LambdaQueryWrapper<SysRoleMenu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.eq(SysRoleMenu::getRoleId, id);
        sysRoleMenuMapper.delete(menuWrapper);

        // 删除角色-权限关联
        LambdaQueryWrapper<SysRolePermission> permissionWrapper = new LambdaQueryWrapper<>();
        permissionWrapper.eq(SysRolePermission::getRoleId, id);
        sysRolePermissionMapper.delete(permissionWrapper);

        // 删除用户-角色关联
        LambdaQueryWrapper<SysUserRole> userRoleWrapper = new LambdaQueryWrapper<>();
        userRoleWrapper.eq(SysUserRole::getRoleId, id);
        sysUserRoleMapper.delete(userRoleWrapper);

        // 删除角色
        removeById(id);
    }

    /**
     * 当前用户可见菜单路径：管理员角色给全部启用菜单；普通角色按 sys_role_menu 授权；
     * 角色没有任何菜单授权时返回空列表（前端回退到岗位默认可见范围）。
     */
    public List<String> getMenuPathsByUserId(Long userId) {
        List<SysRole> roles = getRolesByUserId(userId);
        if (roles.isEmpty()) {
            return List.of();
        }
        if (roles.stream().anyMatch(role -> PositionRoles.isAdminRole(role.getCode()))) {
            return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                            .eq(SysMenu::getStatus, 1)).stream()
                    .map(SysMenu::getPath)
                    .distinct()
                    .toList();
        }
        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        List<Long> menuIds = sysRoleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, roleIds)).stream()
                .map(SysRoleMenu::getMenuId)
                .distinct()
                .toList();
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .in(SysMenu::getId, menuIds)
                        .eq(SysMenu::getStatus, 1)).stream()
                .map(SysMenu::getPath)
                .distinct()
                .toList();
    }

    /** 菜单管理覆盖的所有路径（前端据此区分「受菜单授权管控」与「未纳管」菜单项） */
    public List<String> getManagedMenuPaths() {
        return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .eq(SysMenu::getStatus, 1)).stream()
                .map(SysMenu::getPath)
                .distinct()
                .toList();
    }

    public List<String> getPermissionCodesByUserId(Long userId) {
        List<SysRole> roles = getRolesByUserId(userId);
        if (roles.isEmpty()) {
            return List.of();
        }

        return roles.stream()
                .flatMap(role -> getPermissionsByRoleId(role.getId()).stream())
                .map(SysPermission::getCode)
                .distinct()
                .toList();
    }

    private void ensureDefaultRoles() {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SysRole::getCode, PositionRoles.DEFAULT_ROLE_CODES);
        Map<String, SysRole> existingRoles = list(wrapper).stream()
                .collect(Collectors.toMap(SysRole::getCode, role -> role, (left, right) -> left));

        LocalDateTime now = LocalDateTime.now();
        for (PositionRoles.RolePreset preset : PositionRoles.DEFAULT_ROLE_PRESETS) {
            SysRole role = existingRoles.get(preset.code());
            if (role == null) {
                role = new SysRole();
                role.setCode(preset.code());
                role.setName(preset.name());
                role.setDescription(preset.description());
                role.setStatus(1);
                role.setDataScope(preset.dataScope());
                role.setCreatedAt(now);
                role.setUpdatedAt(now);
                save(role);
                existingRoles.put(preset.code(), role);
                continue;
            }

            boolean changed = false;
            if (!Objects.equals(role.getName(), preset.name())) {
                role.setName(preset.name());
                changed = true;
            }
            if (!Objects.equals(role.getDescription(), preset.description())) {
                role.setDescription(preset.description());
                changed = true;
            }
            if (!Objects.equals(role.getStatus(), 1)) {
                role.setStatus(1);
                changed = true;
            }
            if (!StringUtils.hasText(role.getDataScope())) {
                role.setDataScope(preset.dataScope());
                changed = true;
            }
            if (role.getCreatedAt() == null) {
                role.setCreatedAt(now);
                changed = true;
            }
            if (changed) {
                role.setUpdatedAt(now);
                updateById(role);
            }
        }

        ensureDefaultAuthorization(existingRoles);
    }

    private void ensureDefaultAuthorization(Map<String, SysRole> rolesByCode) {
        List<SysMenu> activeMenus = sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getStatus, 1));
        List<SysPermission> activePermissions = sysPermissionMapper.selectList(new LambdaQueryWrapper<SysPermission>()
                .eq(SysPermission::getStatus, 1));

        Set<Long> allMenuIds = activeMenus.stream()
                .map(SysMenu::getId)
                .collect(Collectors.toSet());
        Set<Long> executionMenuIds = activeMenus.stream()
                .filter(menu -> EXECUTION_MENU_PATHS.contains(menu.getPath()))
                .map(SysMenu::getId)
                .collect(Collectors.toSet());
        Set<Long> allPermissionIds = activePermissions.stream()
                .map(SysPermission::getId)
                .collect(Collectors.toSet());
        Map<String, Long> permissionIdByCode = activePermissions.stream()
                .collect(Collectors.toMap(SysPermission::getCode, SysPermission::getId, (left, right) -> left));

        for (PositionRoles.RolePreset preset : PositionRoles.DEFAULT_ROLE_PRESETS) {
            SysRole role = rolesByCode.get(preset.code());
            if (role == null || role.getId() == null) {
                continue;
            }

            if (MANAGEMENT_ROLE_CODES.contains(preset.code())) {
                addMissingRoleMenus(role.getId(), allMenuIds);
                addMissingRolePermissions(role.getId(), allPermissionIds);
                continue;
            }

            addMissingRoleMenus(role.getId(), executionMenuIds);
            Set<Long> permissionIds = EXECUTION_PERMISSION_CODES.getOrDefault(preset.code(), Collections.emptySet()).stream()
                    .map(permissionIdByCode::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            addMissingRolePermissions(role.getId(), permissionIds);
        }
    }

    private void addMissingRoleMenus(Long roleId, Collection<Long> menuIds) {
        Set<Long> existingMenuIds = sysRoleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toCollection(HashSet::new));

        LocalDateTime now = LocalDateTime.now();
        for (Long menuId : menuIds) {
            if (existingMenuIds.contains(menuId)) {
                continue;
            }
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            roleMenu.setCreatedAt(now);
            sysRoleMenuMapper.insert(roleMenu);
        }
    }

    private void addMissingRolePermissions(Long roleId, Collection<Long> permissionIds) {
        Set<Long> existingPermissionIds = sysRolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                        .eq(SysRolePermission::getRoleId, roleId))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .collect(Collectors.toCollection(HashSet::new));

        LocalDateTime now = LocalDateTime.now();
        for (Long permissionId : permissionIds) {
            if (existingPermissionIds.contains(permissionId)) {
                continue;
            }
            SysRolePermission rolePermission = new SysRolePermission();
            rolePermission.setRoleId(roleId);
            rolePermission.setPermissionId(permissionId);
            rolePermission.setCreatedAt(now);
            sysRolePermissionMapper.insert(rolePermission);
        }
    }

    private SysRole getRoleByCode(String code) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getCode, code);
        return getOne(wrapper, false);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
