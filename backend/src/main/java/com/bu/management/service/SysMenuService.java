package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bu.management.entity.SysMenu;
import com.bu.management.entity.SysRoleMenu;
import com.bu.management.mapper.SysMenuMapper;
import com.bu.management.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysMenuService extends ServiceImpl<SysMenuMapper, SysMenu> {

    private final SysRoleMenuMapper sysRoleMenuMapper;

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

    public List<SysMenu> getAllVisibleMenus() {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getStatus, 1).orderByAsc(SysMenu::getSortOrder);
        return list(wrapper);
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
}
