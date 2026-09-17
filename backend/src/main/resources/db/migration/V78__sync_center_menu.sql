-- V78: 「数据集成中心」菜单与授权（页面在系统管理分组下，/system/sync）
-- sync:manage 接口权限已在 V75 建立，这里补菜单、挂载权限并授权

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT system_menu.id, '数据集成中心', 'CloudSync', '/system/sync', 'SystemSyncView', 107, 1, 1
FROM sys_menu system_menu
WHERE system_menu.path = '/system'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.path = '/system/sync');

-- 将 sync:manage 挂到新菜单（V75 暂挂在 /system/configs 下）
UPDATE sys_permission permission
JOIN sys_menu menu ON menu.path = '/system/sync'
SET permission.menu_id = menu.id
WHERE permission.code = 'sync:manage';

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path = '/system/sync'
WHERE role.code IN ('DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu mapping
      WHERE mapping.role_id = role.id AND mapping.menu_id = menu.id
  );
