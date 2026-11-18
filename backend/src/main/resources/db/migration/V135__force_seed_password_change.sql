-- 种子账号强制改密（2026-11-18）
-- V14 给所有在岗账号写入了初始密码哈希；仍使用它的账号在下次登录时必须改密，
-- 服务端 PasswordChangeGateFilter 会阻断业务 API，避免默认口令长期可用。
-- 不重置密码，只置标记；用户用原密码登录后自行设置新密码。

UPDATE user
SET password_change_required = 1
WHERE password = '$2a$10$/Y4rNsVyq4.y8XD7v9ygZ.jD/Ckn.5amQZdq6oB72t9RxzuX0PV6e'
  AND password_change_required = 0;
