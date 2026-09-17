package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.dto.CreateMenuDTO;
import com.bu.management.dto.ReorderMenuDTO;
import com.bu.management.dto.RoleMenuAssignRequest;
import com.bu.management.dto.UpdateMenuDTO;
import com.bu.management.entity.SysMenu;
import com.bu.management.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/system/menus")
@RequiredArgsConstructor
@RequirePermission({"system:menu:list"})
public class SysMenuController {

    private final SysMenuService sysMenuService;

    @GetMapping
    public ResponseEntity<List<SysMenu>> getAllMenus() {
        return ResponseEntity.ok(sysMenuService.getAllMenus());
    }
    @PostMapping
    @RequirePermission({"system:menu:create"})
    public ResponseEntity<SysMenu> createMenu(@Valid @RequestBody CreateMenuDTO request) {
        return ResponseEntity.ok(sysMenuService.createMenu(
                request.getParentId(),
                request.getName(),
                request.getIcon(),
                request.getPath(),
                request.getComponent(),
                request.getSortOrder(),
                request.getVisible(),
                request.getStatus()
        ));
    }
    @PutMapping("/reorder")
    @RequirePermission({"system:menu:edit"})
    public ResponseEntity<Void> reorderMenus(
            @Valid @RequestBody ReorderMenuDTO request
    ) {
        sysMenuService.reorderMenus(request.getParentId(), request.getMenuIds());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}")
    @RequirePermission({"system:menu:edit"})
    public ResponseEntity<Void> updateMenu(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMenuDTO request
    ) {
        sysMenuService.updateMenu(
                id,
                request.getParentId(),
                request.getName(),
                request.getIcon(),
                request.getPath(),
                request.getComponent(),
                request.getSortOrder(),
                request.getVisible(),
                request.getStatus()
        );
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    @RequirePermission({"system:menu:delete"})
    public ResponseEntity<Void> deleteMenu(@PathVariable Long id) {
        sysMenuService.deleteMenu(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<SysMenu>> getMenusByRoleId(@PathVariable Long roleId) {
        return ResponseEntity.ok(sysMenuService.getMenusByRoleId(roleId));
    }

    @PostMapping("/assign")
    @RequirePermission({"system:menu:edit"})
    public ResponseEntity<Void> assignMenusToRole(@RequestBody RoleMenuAssignRequest request) {
        sysMenuService.assignMenusToRole(request.getRoleId(), request.getMenuIds());
        return ResponseEntity.ok().build();
    }
}
