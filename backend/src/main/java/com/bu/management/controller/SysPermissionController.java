package com.bu.management.controller;

import com.bu.management.entity.SysPermission;
import com.bu.management.service.SysPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/permissions")
@RequiredArgsConstructor
public class SysPermissionController {

    private final SysPermissionService sysPermissionService;

    @GetMapping
    public ResponseEntity<List<SysPermission>> getAllPermissions() {
        return ResponseEntity.ok(sysPermissionService.getAllPermissions());
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<SysPermission>> getPermissionsByType(@PathVariable String type) {
        return ResponseEntity.ok(sysPermissionService.getPermissionsByType(type));
    }

    @GetMapping("/menu/{menuId}")
    public ResponseEntity<List<SysPermission>> getPermissionsByMenuId(@PathVariable Long menuId) {
        return ResponseEntity.ok(sysPermissionService.getPermissionsByMenuId(menuId));
    }
}
