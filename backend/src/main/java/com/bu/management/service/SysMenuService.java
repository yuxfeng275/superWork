package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bu.management.entity.SysMenu;
import com.bu.management.entity.SysPermission;
import com.bu.management.entity.SysRoleMenu;
import com.bu.management.entity.SysRolePermission;
import com.bu.management.mapper.SysMenuMapper;
import com.bu.management.mapper.SysPermissionMapper;
import com.bu.management.mapper.SysRoleMenuMapper;
import com.bu.management.mapper.SysRolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysMenuService extends ServiceImpl<SysMenuMapper, SysMenu> {

    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;

    public List<SysMenu> getMenusByRoleId(Long roleId) {
        LambdaQueryWrapper<SysRoleMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRoleMenu::getRoleId, roleId);
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(wrapper);

        if (roleMenus.isEmpty()) {
            return List.of();
        }

        List<Long> menuIds = roleMenus.stream().map(SysRoleMenu::getMenuId).toList();
        LambdaQueryWrapper<SysMenu> menuWrapper = new LambdaQueryWrapper<>();
        menuWrapper.in(SysMenu::getId, menuIds).eq(SysMenu::getStatus, 1);
        return list(menuWrapper);
    }

    public List<SysMenu> getAllMenus() {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSortOrder)
                .orderByAsc(SysMenu::getId);
        return list(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public SysMenu createMenu(
            Long parentId,
            String name,
            String icon,
            String path,
            String component,
            Integer sortOrder,
            Integer visible,
            Integer status
    ) {
        Long normalizedParentId = normalizeParentId(parentId);
        assertValidParent(normalizedParentId, null);

        SysMenu menu = new SysMenu();
        menu.setParentId(normalizedParentId);
        menu.setName(name.trim());
        menu.setIcon(normalizeText(icon));
        menu.setPath(normalizeText(path));
        menu.setComponent(normalizeText(component));
        menu.setSortOrder(sortOrder != null ? sortOrder : nextSortOrder(normalizedParentId));
        menu.setVisible(visible != null ? visible : 1);
        menu.setStatus(status != null ? status : 1);
        save(menu);
        return menu;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(
            Long id,
            Long parentId,
            String name,
            String icon,
            String path,
            String component,
            Integer sortOrder,
            Integer visible,
            Integer status
    ) {
        SysMenu menu = getById(id);
        if (menu == null) {
            throw new IllegalArgumentException("菜单不存在");
        }

        Long normalizedParentId = normalizeParentId(parentId);
        assertValidParent(normalizedParentId, id);
        menu.setParentId(normalizedParentId);
        menu.setName(name.trim());
        if (icon != null) {
            menu.setIcon(normalizeText(icon));
        }
        menu.setPath(normalizeText(path));
        menu.setComponent(normalizeText(component));
        menu.setSortOrder(sortOrder != null ? sortOrder : 0);
        menu.setVisible(visible != null ? visible : 1);
        menu.setStatus(status != null ? status : 1);
        updateById(menu);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reorderMenus(Long parentId, List<Long> menuIds) {
        Long normalizedParentId = normalizeParentId(parentId);
        assertValidParent(normalizedParentId, null);
        List<SysMenu> siblings = lambdaQuery()
                .eq(SysMenu::getParentId, normalizedParentId)
                .list();
        Set<Long> siblingIds = siblings.stream()
                .map(SysMenu::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (siblingIds.size() != menuIds.size()
                || !siblingIds.equals(new HashSet<>(menuIds))) {
            throw new IllegalArgumentException("排序列表必须包含同级全部菜单");
        }
        for (int index = 0; index < menuIds.size(); index++) {
            SysMenu menu = getById(menuIds.get(index));
            menu.setSortOrder(index + 1);
            updateById(menu);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long id) {
        SysMenu menu = getById(id);
        if (menu == null) {
            throw new IllegalArgumentException("菜单不存在");
        }

        Long childCount = lambdaQuery()
                .eq(SysMenu::getParentId, id)
                .count();
        if (childCount > 0) {
            throw new IllegalArgumentException("请先删除或移动子菜单");
        }

        sysRoleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>()
                .eq(SysRoleMenu::getMenuId, id));

        List<SysPermission> permissions = sysPermissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getMenuId, id));
        for (SysPermission permission : permissions) {
            sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>()
                    .eq(SysRolePermission::getPermissionId, permission.getId()));
            sysPermissionMapper.deleteById(permission.getId());
        }
        removeById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMenusToRole(Long roleId, List<Long> menuIds) {
        LambdaQueryWrapper<SysRoleMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRoleMenu::getRoleId, roleId);
        sysRoleMenuMapper.delete(wrapper);

        for (Long menuId : menuIds) {
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(roleId);
            roleMenu.setMenuId(menuId);
            sysRoleMenuMapper.insert(roleMenu);
        }
    }

    private Integer nextSortOrder(Long parentId) {
        return lambdaQuery()
                .eq(SysMenu::getParentId, parentId)
                .select(SysMenu::getSortOrder)
                .list()
                .stream()
                .map(SysMenu::getSortOrder)
                .filter(value -> value != null)
                .max(Integer::compareTo)
                .map(value -> value + 1)
                .orElse(1);
    }

    private void assertValidParent(Long parentId, Long menuId) {
        if (parentId == 0) {
            return;
        }

        Set<Long> visited = new HashSet<>();
        Long currentId = parentId;
        while (currentId != null && currentId != 0 && visited.add(currentId)) {
            if (currentId.equals(menuId)) {
                throw new IllegalArgumentException("菜单不能挂载到自身或子菜单下");
            }
            SysMenu parent = getById(currentId);
            if (parent == null) {
                throw new IllegalArgumentException("父菜单不存在");
            }
            currentId = normalizeParentId(parent.getParentId());
        }
        if (currentId != null && currentId != 0) {
            throw new IllegalArgumentException("菜单层级存在循环");
        }
    }

    private Long normalizeParentId(Long parentId) {
        return parentId == null ? 0L : parentId;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
