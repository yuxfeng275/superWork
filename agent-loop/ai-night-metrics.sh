#!/bin/sh
# 夜间批运行指标（取自 241 线上库，不估算）。
#
# 用法：在能 ssh 241 的机器上执行
#   ./agent-loop/ai-night-metrics.sh [天数，默认 7]
#
# 输出：每天一次夜间批的结果、成功率、未知调用数、制品/晨报数、待审批数。
set -eu
DAYS="${1:-7}"

ssh 241 "docker exec -i superwork-bu-mysql sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -B bu_management'" <<SQL
SELECT '=== 近 $DAYS 天夜间批 ===';
SELECT biz_date, status, run_no, started_at, finished_at,
       TIMESTAMPDIFF(MINUTE, started_at, finished_at) AS minutes
FROM ai_run
WHERE kind = 'NIGHT_BATCH' AND biz_date >= DATE_SUB(CURDATE(), INTERVAL $DAYS DAY)
ORDER BY biz_date DESC, id DESC;

SELECT '=== 成功率（成功或部分即算跑完） ===';
SELECT COUNT(*) AS runs,
       SUM(status IN ('SUCCEEDED','PARTIAL')) AS finished,
       ROUND(100 * SUM(status IN ('SUCCEEDED','PARTIAL')) / GREATEST(COUNT(*), 1), 1) AS success_pct
FROM ai_run
WHERE kind = 'NIGHT_BATCH' AND biz_date >= DATE_SUB(CURDATE(), INTERVAL $DAYS DAY);

SELECT '=== 未知结果调用（必须人工可见） ===';
SELECT status, COUNT(*) FROM ai_invocation
WHERE run_id IN (SELECT id FROM ai_run WHERE biz_date >= DATE_SUB(CURDATE(), INTERVAL $DAYS DAY))
GROUP BY status;

SELECT '=== 制品与晨报 ===';
SELECT (SELECT COUNT(*) FROM ai_artifact WHERE created_at >= DATE_SUB(NOW(), INTERVAL $DAYS DAY)) AS artifacts,
       (SELECT COUNT(*) FROM ai_brief WHERE created_at >= DATE_SUB(NOW(), INTERVAL $DAYS DAY)) AS briefs,
       (SELECT COUNT(*) FROM ai_brief WHERE created_at >= DATE_SUB(NOW(), INTERVAL $DAYS DAY) AND delivered_at IS NOT NULL) AS briefs_delivered;

SELECT '=== 待人工处理 ===';
SELECT COUNT(*) AS pending_gates FROM ai_gate WHERE status = 'PENDING';
SELECT COUNT(*) AS artifacts_in_review FROM ai_artifact WHERE status = 'IN_REVIEW';
SQL
