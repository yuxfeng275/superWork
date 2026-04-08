package com.bu.management.controller;

import com.bu.management.dto.RoleMenuAssignRequest;
import com.bu.management.entity.SysMenu;
import com.bu.management.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/menus")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService sysMenuService;

    @GetMapping
    public ResponseEntity<List<SysMenu>> getAllMenus() {
        return ResponseEntity.ok(sysMenuService.getAllVisibleMenus());
    }

    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<SysMenu>> getMenusByRoleId(@PathVariable Long roleId) {
        return ResponseEntity.ok(sysMenuService.getMenusByRoleId(roleId));
    }

    @PostMapping("/assign")
    public ResponseEntity<Void> assignMenusToRole(@RequestBody RoleMenuAssignRequest request) {
        sysMenuService.assignMenusToRole(request.getRoleId(), request.getMenuIds());
        return ResponseEntity.ok().build();
    }
}
