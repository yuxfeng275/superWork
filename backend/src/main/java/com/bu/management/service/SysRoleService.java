package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bu.management.entity.SysPermission;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.SysRoleMenu;
import com.bu.management.entity.SysRolePermission;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysRoleMenuMapper;
import com.bu.management.mapper.SysPermissionMapper;
import com.bu.management.mapper.SysRoleMapper;
import com.bu.management.mapper.SysRolePermissionMapper;
import com.bu.management.mapper.SysUserRoleMapper;
import com.bu.management.vo.RoleAuthorizationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleService extends ServiceImpl<SysRoleMapper, SysRole> {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    public List<SysRole> getAllRoles() {
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
        roleWrapper.in(SysRole::getId, roleIds);
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

    public void createRole(String code, String name, String description, Integer status) {
        SysRole role = new SysRole();
        role.setCode(code);
        role.setName(name);
        role.setDescription(description);
        role.setStatus(status != null ? status : 1);
        save(role);
    }

    public void updateRole(Long id, String name, String description, Integer status) {
        SysRole role = getById(id);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在");
        }
        role.setName(name);
        role.setDescription(description);
        if (status != null) {
            role.setStatus(status);
        }
        updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
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

    private <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
