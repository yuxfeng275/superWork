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

# === 验收判定（成功标准 4 的口径） ===
# 成功率 = 近 N 天「跑完」的夜间批 / 全部夜间批（SUCCEEDED 或 PARTIAL 都算跑完）
# 未知可见 = 有 STARTED/UNKNOWN 的调用时，对应节点必须是 UNKNOWN/FAILED（页面可点开看到）
# 审批时效 = 已决策制品的「创建 → 决策」中位数小时
ssh 241 "docker exec -i superwork-bu-mysql sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -B bu_management'" <<SQL
SELECT '=== 验收判定 ===';
SELECT
  CONCAT('成功率 ', ROUND(100*SUM(status IN ('SUCCEEDED','PARTIAL'))/GREATEST(COUNT(*),1),1), '%',
         ' （阈值 90%）  => ',
         IF(ROUND(100*SUM(status IN ('SUCCEEDED','PARTIAL'))/GREATEST(COUNT(*),1),1) >= 90, 'PASS', 'FAIL')) AS verdict
FROM ai_run WHERE kind='NIGHT_BATCH' AND biz_date >= DATE_SUB(CURDATE(), INTERVAL $DAYS DAY);

SELECT
  CONCAT('未知调用 ', COUNT(*), ' 次；其中未在节点上显式可见的 ',
         COALESCE(SUM(node_id NOT IN (SELECT id FROM ai_run_node WHERE status IN ('UNKNOWN','FAILED'))), 0), ' 次',
         '  => ', IF(COALESCE(SUM(node_id NOT IN (SELECT id FROM ai_run_node WHERE status IN ('UNKNOWN','FAILED'))), 0) = 0, 'PASS', 'FAIL')) AS verdict
FROM ai_invocation
WHERE status IN ('STARTED','UNKNOWN')
  AND run_id IN (SELECT id FROM ai_run WHERE biz_date >= DATE_SUB(CURDATE(), INTERVAL $DAYS DAY));

SELECT
  CONCAT('审批时效中位数 ', COALESCE(ROUND(AVG(TIMESTAMPDIFF(MINUTE, created_at, decided_at))/60, 1), 0), ' 小时（样本 ',
         COUNT(*), ' 条）') AS verdict
FROM ai_artifact
WHERE decided_at IS NOT NULL AND created_at >= DATE_SUB(NOW(), INTERVAL $DAYS DAY);
SQL
