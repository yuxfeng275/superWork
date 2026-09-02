package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.SysMenu;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.SysRoleMenu;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysMenuMapper;
import com.bu.management.mapper.SysPermissionMapper;
import com.bu.management.mapper.SysRoleMenuMapper;
import com.bu.management.mapper.SysRolePermissionMapper;
import com.bu.management.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysRoleMenuPathsTest {

    @Mock private SysUserRoleMapper sysUserRoleMapper;
    @Mock private SysMenuMapper sysMenuMapper;
    @Mock private SysRoleMenuMapper sysRoleMenuMapper;
    @Mock private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock private SysPermissionMapper sysPermissionMapper;

    private SysRoleService service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new SysRoleService(
                sysUserRoleMapper, sysMenuMapper, sysRoleMenuMapper, sysRolePermissionMapper, sysPermissionMapper));
    }

    private SysRole role(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setStatus(1);
        return role;
    }

    private SysRoleMenu roleMenu(Long roleId, Long menuId) {
        SysRoleMenu roleMenu = new SysRoleMenu();
        roleMenu.setRoleId(roleId);
        roleMenu.setMenuId(menuId);
        return roleMenu;
    }

    private SysMenu menu(Long id, String path) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setPath(path);
        menu.setStatus(1);
        return menu;
    }

    private void stubRoles(Long userId, List<SysRole> roles) {
        List<SysUserRole> links = roles.stream().map(role -> {
            SysUserRole link = new SysUserRole();
            link.setUserId(userId);
            link.setRoleId(role.getId());
            return link;
        }).toList();
        when(sysUserRoleMapper.selectList(any())).thenReturn(links);
        Mockito.doReturn(roles).when(service).list(any(LambdaQueryWrapper.class));
    }

    @Test
    void adminRoleGetsAllEnabledMenus() {
        stubRoles(7L, List.of(role(1L, "DIRECTOR")));
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(menu(1L, "/home"), menu(2L, "/tasks")));

        assertThat(service.getMenuPathsByUserId(7L)).containsExactlyInAnyOrder("/home", "/tasks");
    }

    @Test
    void normalRoleGetsAssignedMenuPaths() {
        stubRoles(7L, List.of(role(2L, "FULL_STACK_ENGINEER")));
        when(sysRoleMenuMapper.selectList(any())).thenReturn(List.of(roleMenu(2L, 3L), roleMenu(2L, 4L)));
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(menu(3L, "/tasks"), menu(4L, "/defects")));

        assertThat(service.getMenuPathsByUserId(7L)).containsExactlyInAnyOrder("/tasks", "/defects");
    }

    @Test
    void roleWithoutAssignmentsGetsEmptyList() {
        stubRoles(7L, List.of(role(2L, "FULL_STACK_ENGINEER")));
        when(sysRoleMenuMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.getMenuPathsByUserId(7L)).isEmpty();
    }

    @Test
    void userWithoutRolesGetsEmptyList() {
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.getMenuPathsByUserId(7L)).isEmpty();
    }
}
