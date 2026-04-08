package com.bu.management.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.CreateRoleDTO;
import com.bu.management.dto.RoleAuthorizationAssignRequest;
import com.bu.management.dto.RolePermissionAssignRequest;
import com.bu.management.dto.UpdateRoleDTO;
import com.bu.management.dto.UserRoleAssignRequest;
import com.bu.management.entity.SysPermission;
import com.bu.management.entity.SysRole;
import com.bu.management.entity.SysUserRole;
import com.bu.management.mapper.SysUserRoleMapper;
import com.bu.management.service.SysRoleService;
import com.bu.management.vo.RoleAuthorizationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/system/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService sysRoleService;
    private final SysUserRoleMapper sysUserRoleMapper;

    @GetMapping
    public ResponseEntity<List<SysRole>> getAllRoles() {
        return ResponseEntity.ok(sysRoleService.getAllRoles());
    }

    @PostMapping
    public ResponseEntity<SysRole> createRole(@Valid @RequestBody CreateRoleDTO request) {
        sysRoleService.createRole(request.getCode(), request.getName(), request.getDescription(), request.getStatus());
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRole::getCode, request.getCode());
        SysRole role = sysRoleService.getOne(wrapper);
        return ResponseEntity.ok(role);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleDTO request) {
        sysRoleService.updateRole(id, request.getName(), request.getDescription(), request.getStatus());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRole(@PathVariable Long id) {
        sysRoleService.deleteRole(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<SysRole>> getRolesByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(sysRoleService.getRolesByUserId(userId));
    }

    @GetMapping("/{roleId}/permissions")
    public ResponseEntity<List<SysPermission>> getPermissionsByRoleId(@PathVariable Long roleId) {
        return ResponseEntity.ok(sysRoleService.getPermissionsByRoleId(roleId));
    }

    @GetMapping("/{roleId}/authorization")
    public ResponseEntity<RoleAuthorizationVO> getAuthorizationByRoleId(@PathVariable Long roleId) {
        return ResponseEntity.ok(sysRoleService.getAuthorizationByRoleId(roleId));
    }

    @PostMapping("/permissions/assign")
    public ResponseEntity<Void> assignPermissionsToRole(@RequestBody RolePermissionAssignRequest request) {
        sysRoleService.assignPermissionsToRole(request.getRoleId(), request.getPermissionIds());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/authorization/assign")
    public ResponseEntity<Void> assignAuthorizationToRole(@RequestBody RoleAuthorizationAssignRequest request) {
        sysRoleService.assignAuthorizationToRole(
                request.getRoleId(),
                request.getMenuIds(),
                request.getPermissionIds(),
                request.getDataScope(),
                request.getDataScopeValue()
        );
        return ResponseEntity.ok().build();
    }

    @PostMapping("/user/assign")
    public ResponseEntity<Void> assignRolesToUser(@RequestBody UserRoleAssignRequest request) {
        // 先删除用户的所有角色
        LambdaQueryWrapper<SysUserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUserRole::getUserId, request.getUserId());
        sysUserRoleMapper.delete(wrapper);
        // 重新分配
        for (Long roleId : request.getRoleIds()) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(request.getUserId());
            userRole.setRoleId(roleId);
            sysUserRoleMapper.insert(userRole);
        }
        return ResponseEntity.ok().build();
    }
}
