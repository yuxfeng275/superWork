-- V85: 站内 @ 待办
-- 周进展 / 周会文本中 @系统用户 后，为被提及人生成一条待办；
-- 消息铃铛聚合未读 OPEN 待办，工作菜单提供独立待办页。

CREATE TABLE IF NOT EXISTS user_todo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assignee_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NOT NULL,
    mention_token VARCHAR(80) NOT NULL,
    title VARCHAR(240) NOT NULL,
    excerpt TEXT NULL,
    link VARCHAR(255) NULL,
    source_date DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    UNIQUE KEY uk_user_todo_source_assignee (source_type, source_id, assignee_id),
    INDEX idx_user_todo_assignee_status (assignee_id, status, created_at),
    CONSTRAINT fk_user_todo_assignee FOREIGN KEY (assignee_id) REFERENCES user(id),
    CONSTRAINT fk_user_todo_actor FOREIGN KEY (actor_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内@待办';

INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)
SELECT (SELECT t.id FROM (SELECT id FROM sys_menu WHERE path = '/sec-work' LIMIT 1) t),
       '待办', 'Finished', '/todos', 'TodosView', 2, 1, 1
FROM dual
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/todos');

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'todo:view', '查看待办', '查看自己被 @ 的待办并完成', 'menu', menu.id
FROM sys_menu menu WHERE menu.path = '/todos'
  AND NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.code = 'todo:view');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r
JOIN sys_menu m ON m.path = '/todos';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r
JOIN sys_permission p ON p.code = 'todo:view';
