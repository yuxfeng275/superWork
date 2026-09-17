-- 将业务线、项目、客户信息调整到基础分类下，并补齐缺失菜单

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '基础分类', 'Collection', '/base', NULL, 6, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/base'
);

UPDATE sys_menu
SET name = '业务线管理',
    icon = 'CollectionTag',
    path = '/business-lines',
    component = 'BusinessLineView',
    sort_order = 61
WHERE path = '/organization';

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '业务线管理', 'CollectionTag', '/business-lines', 'BusinessLineView', 61, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/business-lines'
);

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '项目管理', 'Folder', '/projects', 'ProjectView', 62, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/projects'
);

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT 0, '客户信息管理', 'UserFilled', '/customers', 'CustomerInfoView', 63, 1, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM sys_menu WHERE path = '/customers'
);

UPDATE sys_menu child
JOIN (
    SELECT id
    FROM sys_menu
    WHERE path = '/base'
    LIMIT 1
) base ON 1 = 1
SET child.parent_id = base.id
WHERE child.path IN ('/business-lines', '/projects', '/customers');

UPDATE sys_permission
SET name = '查看业务线列表',
    description = '查看业务线列表',
    menu_id = (
        SELECT id
        FROM (
            SELECT id
            FROM sys_menu
            WHERE path = '/business-lines'
            LIMIT 1
        ) business_line_menu
    )
WHERE code = 'org:view';

UPDATE sys_permission
SET name = '编辑业务线',
    description = '编辑业务线信息',
    menu_id = (
        SELECT id
        FROM (
            SELECT id
            FROM sys_menu
            WHERE path = '/business-lines'
            LIMIT 1
        ) business_line_menu
    )
WHERE code = 'org:edit';

INSERT INTO sys_permission (code, name, description, type, menu_id, status)
SELECT 'project:view', '查看项目列表', '查看项目列表', 'menu', menu.id, 1
FROM sys_menu menu
WHERE menu.path = '/projects'
  AND NOT EXISTS (
      SELECT 1 FROM sys_permission WHERE code = 'project:view'
  );

INSERT INTO sys_permission (code, name, description, type, menu_id, status)
SELECT 'customer-contact:view', '查看客户信息列表', '查看客户信息列表', 'menu', menu.id, 1
FROM sys_menu menu
WHERE menu.path = '/customers'
  AND NOT EXISTS (
      SELECT 1 FROM sys_permission WHERE code = 'customer-contact:view'
  );

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.path IN ('/base', '/business-lines', '/projects', '/customers')
WHERE role.code = 'BU_ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_menu role_menu
      WHERE role_menu.role_id = role.id
        AND role_menu.menu_id = menu.id
  );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.code IN ('project:view', 'customer-contact:view')
WHERE role.code = 'BU_ADMIN'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_permission role_permission
      WHERE role_permission.role_id = role.id
        AND role_permission.permission_id = permission.id
  );
