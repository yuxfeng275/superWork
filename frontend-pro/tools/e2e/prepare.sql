-- 只重置 E2E 测试账号 e2e_admin 的 fixture，不碰真实用户数据。
-- 用法：docker exec -i superwork-bu-mysql sh -c 'mysql -ubu_admin -p"$MYSQL_PASSWORD" bu_management' < prepare.sql
UPDATE ai_proactive_suggestion
SET status = 'NEW', updated_at = NOW()
WHERE user_id = 29 AND dedupe_key = 'e2e-fixture-2026-11-18';
