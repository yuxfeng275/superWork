-- ====================================
-- V14: 按 2026-07-27 人员岗位清单重置账号
-- 保留 admin 原记录，其余账号统一为姓名全拼和初始密码 123456
-- ====================================

-- 1. 释放现有非管理员用户名，便于复用仍在岗人员的原用户 ID
UPDATE user
SET username = CONCAT('__legacy_', id),
    email = NULL,
    phone = NULL
WHERE LOWER(username) <> 'admin';

-- 2. 仍在岗人员沿用原用户 ID，保留历史业务归属
UPDATE user
SET username = CASE real_name
        WHEN '小刘洋' THEN 'xiaoliuyang'
        WHEN '田蜜' THEN 'tianmi'
        WHEN '丛宁' THEN 'congning'
        WHEN '姜涛' THEN 'jiangtao'
        WHEN '大刘洋' THEN 'daliuyang'
        WHEN '张野' THEN 'zhangye'
        WHEN '石家乐' THEN 'shijiale'
        WHEN '刘双升' THEN 'liushuangsheng'
        WHEN '任作伟' THEN 'renzuowei'
        WHEN '王昆' THEN 'wangkun'
        WHEN '崔皓翔' THEN 'cuihaoxiang'
        WHEN '多俊杰' THEN 'duojunjie'
        WHEN '李芳晨' THEN 'lifangchen'
        WHEN '黄金玲' THEN 'huangjinling'
        WHEN '于峰' THEN 'yufeng'
        WHEN '张群成' THEN 'zhangquncheng'
    END,
    password = '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e',
    role = CASE
        WHEN real_name IN ('小刘洋', '田蜜', '丛宁', '姜涛') THEN 'SOLUTION_MANAGER'
        WHEN real_name IN ('大刘洋', '张野') THEN 'TECH_ARCHITECT'
        WHEN real_name IN ('石家乐', '刘双升', '任作伟', '王昆', '崔皓翔') THEN 'FULL_STACK_ENGINEER'
        WHEN real_name IN ('多俊杰', '李芳晨') THEN 'AI_OPERATIONS_ENGINEER'
        WHEN real_name = '黄金玲' THEN 'QUALITY_ENGINEER'
        WHEN real_name IN ('于峰', '张群成') THEN 'BUSINESS_OWNER'
    END,
    status = 1
WHERE username LIKE '__legacy_%'
  AND real_name IN (
      '小刘洋', '田蜜', '丛宁', '姜涛',
      '大刘洋', '张野',
      '石家乐', '刘双升', '任作伟', '王昆', '崔皓翔',
      '多俊杰', '李芳晨',
      '黄金玲',
      '于峰', '张群成'
  );

-- 3. 补齐清单中尚不存在的账号
INSERT INTO user (username, password, real_name, role, email, phone, status) VALUES
('xiaoliuyang', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '小刘洋', 'SOLUTION_MANAGER', NULL, NULL, 1),
('tianmi', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '田蜜', 'SOLUTION_MANAGER', NULL, NULL, 1),
('congning', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '丛宁', 'SOLUTION_MANAGER', NULL, NULL, 1),
('jiangtao', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '姜涛', 'SOLUTION_MANAGER', NULL, NULL, 1),
('daliuyang', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '大刘洋', 'TECH_ARCHITECT', NULL, NULL, 1),
('zhangye', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '张野', 'TECH_ARCHITECT', NULL, NULL, 1),
('shijiale', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '石家乐', 'FULL_STACK_ENGINEER', NULL, NULL, 1),
('liushuangsheng', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '刘双升', 'FULL_STACK_ENGINEER', NULL, NULL, 1),
('renzuowei', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '任作伟', 'FULL_STACK_ENGINEER', NULL, NULL, 1),
('wangkun', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '王昆', 'FULL_STACK_ENGINEER', NULL, NULL, 1),
('cuihaoxiang', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '崔皓翔', 'FULL_STACK_ENGINEER', NULL, NULL, 1),
('duojunjie', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '多俊杰', 'AI_OPERATIONS_ENGINEER', NULL, NULL, 1),
('lifangchen', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '李芳晨', 'AI_OPERATIONS_ENGINEER', NULL, NULL, 1),
('huangjinling', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '黄金玲', 'QUALITY_ENGINEER', NULL, NULL, 1),
('yufeng', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '于峰', 'BUSINESS_OWNER', NULL, NULL, 1),
('zhangquncheng', '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e', '张群成', 'BUSINESS_OWNER', NULL, NULL, 1)
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    real_name = VALUES(real_name),
    role = VALUES(role),
    email = NULL,
    phone = NULL,
    status = 1;

-- 4. 已离岗账号的历史引用统一归到 admin，项目成员关系直接移除
SET @admin_user_id = (SELECT id FROM user WHERE username = 'admin' LIMIT 1);

DELETE project_member
FROM project_member
JOIN user legacy_user ON legacy_user.id = project_member.user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE project
JOIN user legacy_user ON legacy_user.id = project.manager_id
SET project.manager_id = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE requirement
JOIN user legacy_user ON legacy_user.id = requirement.creator_id
SET requirement.creator_id = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE requirement_evaluation
LEFT JOIN user legacy_evaluator
    ON legacy_evaluator.id = requirement_evaluation.evaluator_id
   AND legacy_evaluator.username LIKE '__legacy_%'
LEFT JOIN user legacy_decider
    ON legacy_decider.id = requirement_evaluation.decision_by
   AND legacy_decider.username LIKE '__legacy_%'
SET requirement_evaluation.evaluator_id =
        CASE WHEN legacy_evaluator.id IS NOT NULL THEN @admin_user_id ELSE requirement_evaluation.evaluator_id END,
    requirement_evaluation.decision_by =
        CASE WHEN legacy_decider.id IS NOT NULL THEN @admin_user_id ELSE requirement_evaluation.decision_by END
WHERE legacy_evaluator.id IS NOT NULL
   OR legacy_decider.id IS NOT NULL;

UPDATE design_work_log
JOIN user legacy_user ON legacy_user.id = design_work_log.designer_id
SET design_work_log.designer_id = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE requirement_confirmation
JOIN user legacy_user ON legacy_user.id = requirement_confirmation.confirmed_by
SET requirement_confirmation.confirmed_by = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE requirement_delivery
LEFT JOIN user legacy_deliverer
    ON legacy_deliverer.id = requirement_delivery.delivered_by
   AND legacy_deliverer.username LIKE '__legacy_%'
LEFT JOIN user legacy_accepter
    ON legacy_accepter.id = requirement_delivery.accepted_by
   AND legacy_accepter.username LIKE '__legacy_%'
SET requirement_delivery.delivered_by =
        CASE WHEN legacy_deliverer.id IS NOT NULL THEN @admin_user_id ELSE requirement_delivery.delivered_by END,
    requirement_delivery.accepted_by =
        CASE WHEN legacy_accepter.id IS NOT NULL THEN @admin_user_id ELSE requirement_delivery.accepted_by END
WHERE legacy_deliverer.id IS NOT NULL
   OR legacy_accepter.id IS NOT NULL;

UPDATE task
LEFT JOIN user legacy_assignee
    ON legacy_assignee.id = task.assignee_id
   AND legacy_assignee.username LIKE '__legacy_%'
LEFT JOIN user legacy_creator
    ON legacy_creator.id = task.created_by
   AND legacy_creator.username LIKE '__legacy_%'
SET task.assignee_id =
        CASE WHEN legacy_assignee.id IS NOT NULL THEN @admin_user_id ELSE task.assignee_id END,
    task.created_by =
        CASE WHEN legacy_creator.id IS NOT NULL THEN @admin_user_id ELSE task.created_by END
WHERE legacy_assignee.id IS NOT NULL
   OR legacy_creator.id IS NOT NULL;

UPDATE work_log
JOIN user legacy_user ON legacy_user.id = work_log.user_id
SET work_log.user_id = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

UPDATE issue
LEFT JOIN user legacy_assignee
    ON legacy_assignee.id = issue.assignee_id
   AND legacy_assignee.username LIKE '__legacy_%'
LEFT JOIN user legacy_creator
    ON legacy_creator.id = issue.creator_id
   AND legacy_creator.username LIKE '__legacy_%'
SET issue.assignee_id =
        CASE WHEN legacy_assignee.id IS NOT NULL THEN @admin_user_id ELSE issue.assignee_id END,
    issue.creator_id =
        CASE WHEN legacy_creator.id IS NOT NULL THEN @admin_user_id ELSE issue.creator_id END
WHERE legacy_assignee.id IS NOT NULL
   OR legacy_creator.id IS NOT NULL;

UPDATE attachment
JOIN user legacy_user ON legacy_user.id = attachment.uploaded_by
SET attachment.uploaded_by = @admin_user_id
WHERE legacy_user.username LIKE '__legacy_%';

-- 5. 重建非管理员用户的单岗位关系，再删除旧账号
DELETE user_role
FROM sys_user_role user_role
JOIN user u ON u.id = user_role.user_id
WHERE LOWER(u.username) <> 'admin';

DELETE FROM user
WHERE username LIKE '__legacy_%';

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, role.id
FROM user u
JOIN sys_role role ON role.code = u.role
WHERE LOWER(u.username) <> 'admin'
  AND u.username IN (
      'xiaoliuyang', 'tianmi', 'congning', 'jiangtao',
      'daliuyang', 'zhangye',
      'shijiale', 'liushuangsheng', 'renzuowei', 'wangkun', 'cuihaoxiang',
      'duojunjie', 'lifangchen',
      'huangjinling',
      'yufeng', 'zhangquncheng'
  );
