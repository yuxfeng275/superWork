#!/bin/sh
# agent-autoloop 的「只触发」入口：规则里不再写流程，只让合拍开始今晚的夜间批。
#
# 用法（rules.json 的 command）：
#   /home/openclaw/huji-agent/night-batch-trigger.sh {prompt}
#
# 约定：向 stdout 打印最后一条结论；失败打印原因并以非 0 退出（中台只看最后一条回复）。
# 环境：复用同目录 env.sh 里的 HUJI_BASE_URL / HUJI_TOKEN / HUJI_AGENT_USER_ID / HUJI_PROJECT_ID。
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$HERE/env.sh" ]; then
  . "$HERE/env.sh"
fi

HUJI_BASE_URL="${HUJI_BASE_URL:?缺少 HUJI_BASE_URL}"
HUJI_AGENT_USER_ID="${HUJI_AGENT_USER_ID:?缺少 HUJI_AGENT_USER_ID（夜间任务必须按成员身份运行）}"
HUJI_PROJECT_ID="${HUJI_PROJECT_ID:?缺少 HUJI_PROJECT_ID}"
HUJI_TOKEN="${HUJI_TOKEN:-}"
BIZ_DATE="${HUJI_BIZ_DATE:-$(date +%F)}"

fail() {
  echo "失败：$1" >&2
  echo "失败：$1"
  exit 1
}

json=$(cat <<EOF
{"userId":$HUJI_AGENT_USER_ID,"projectId":$HUJI_PROJECT_ID,"bizDate":"$BIZ_DATE"}
EOF
)

response=$(curl -sS -m 1800 -X POST "${HUJI_BASE_URL%/}/internal/project-context/night-batch" \
  -H "Content-Type: application/json" \
  ${HUJI_TOKEN:+-H "X-Sidecar-Token: $HUJI_TOKEN"} \
  -d "$json") || fail "请求合拍失败（网络或超时）"

# 合拍返回 Result 包装：成功时 data 里是 run；失败时 message 是原因
run_id=$(printf '%s' "$response" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p' | head -1)
run_status=$(printf '%s' "$response" | sed -n 's/.*"status":"\([A-Z_]*\)".*/\1/p' | head -1)
run_no=$(printf '%s' "$response" | sed -n 's/.*"runNo":"\([^"]*\)".*/\1/p' | head -1)
message=$(printf '%s' "$response" | sed -n 's/.*"message":"\([^"]*\)".*/\1/p' | head -1)

if [ -z "$run_id" ]; then
  fail "${message:-返回里没有 run（$response）}"
fi

echo "夜间批已由合拍编排：$run_no（runId=$run_id，状态=$run_status）"
