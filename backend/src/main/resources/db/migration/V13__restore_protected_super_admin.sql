-- ====================================
-- V13: 恢复受保护超级管理员账号
-- admin / 123456
-- ====================================

INSERT INTO sys_role (code, name, description, status, data_scope, data_scope_value) VALUES
('DIRECTOR', '总监', '部门第一负责人，对整体经营结果、能力建设质量负责', 1, 'ALL', NULL)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    status = 1,
    data_scope = 'ALL',
    data_scope_value = NULL;

INSERT INTO user (username, password, real_name, role, email, phone, status) VALUES
('admin', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '系统管理员', 'DIRECTOR', 'admin@bu.com', '13800000001', 1)
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    real_name = VALUES(real_name),
    role = 'DIRECTOR',
    email = VALUES(email),
    phone = VALUES(phone),
    status = 1;

DELETE user_role
FROM sys_user_role user_role
JOIN user u ON u.id = user_role.user_id
WHERE u.username = 'admin';

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, role.id
FROM user u
JOIN sys_role role ON role.code = 'DIRECTOR'
WHERE u.username = 'admin';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.id, menu.id
FROM sys_role role
JOIN sys_menu menu ON menu.status = 1
WHERE role.code = 'DIRECTOR';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.status = 1
WHERE role.code = 'DIRECTOR';
