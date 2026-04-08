package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bu.management.entity.SysPermission;
import com.bu.management.mapper.SysPermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysPermissionService extends ServiceImpl<SysPermissionMapper, SysPermission> {

    public List<SysPermission> getAllPermissions() {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPermission::getStatus, 1);
        return list(wrapper);
    }

    public List<SysPermission> getPermissionsByType(String type) {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPermission::getStatus, 1).eq(SysPermission::getType, type);
        return list(wrapper);
    }

    public List<SysPermission> getPermissionsByMenuId(Long menuId) {
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysPermission::getMenuId, menuId);
        return list(wrapper);
    }
}
