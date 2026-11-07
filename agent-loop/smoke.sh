#!/usr/bin/env bash
# M0 端到端冒烟：事实汇入 → 事实读取 → 写晨报 → 读晨报。
#
# 用法（在能访问后端的机器上）：
#   HUJI_BASE_URL=http://127.0.0.1:8081 \
#   HUJI_TOKEN=<project-context.token> \
#   PROJECT_ID=<项目 id> \
#   TEST_USER_ID=<试点成员 userId> \
#   HUJI_JWT=<可选，登录后的 access token> \
#   bash agent-loop/smoke.sh
#
# 任何一步非 2xx 都会打印响应体并以非 0 退出。
# 第 4 步需要登录态：没给 HUJI_JWT 时自动跳过（打印 SKIP，不影响前面三步的结论）。
set -uo pipefail

BASE="${HUJI_BASE_URL:-http://127.0.0.1:8081}"
TOKEN="${HUJI_TOKEN:-}"
PROJECT_ID="${PROJECT_ID:-}"
TEST_USER_ID="${TEST_USER_ID:-}"
JWT="${HUJI_JWT:-}"
TODAY="$(date +%Y-%m-%d)"
MARKER="smoke 测试晨报（可删除）"
# 与夜间规则写出的格式一致，顺便验证 content 能存下结构化 JSON（推送时按纯文本渲染）
CONTENT="{\"items\":[{\"title\":\"$MARKER\",\"why\":\"端到端冒烟，验证事实→晨报→读取链路\",\"links\":[]}]}"

if [ -z "$PROJECT_ID" ]; then
  echo "缺少 PROJECT_ID（例：PROJECT_ID=1）" >&2
  exit 2
fi
if [ -z "$TEST_USER_ID" ]; then
  echo "缺少 TEST_USER_ID（试点成员的 userId）" >&2
  exit 2
fi
if [ -z "$TOKEN" ]; then
  echo "警告：HUJI_TOKEN 为空 —— 仅当后端未配置 project-context.token 时才会放行" >&2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body.json"
CODE=""

# request METHOD PATH [JSON_BODY] [EXTRA_HEADER]
request() {
  local method="$1" path="$2" data="${3:-}" extra="${4:-}"
  local args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path"
              --connect-timeout 10 --max-time 180
              -H 'Accept: application/json')
  [ -n "$TOKEN" ] && args+=(-H "X-Sidecar-Token: $TOKEN")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data "$data")
  [ -n "$extra" ] && args+=(-H "$extra")
  CODE="$(curl "${args[@]}")" || { echo "✗ curl 调用失败：$method $path" >&2; exit 1; }
}

# 把任意文本转义成可嵌进 JSON 字符串的形式（冒号/换行都吃掉）
json_escape() {
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\n\r'
}

expect_ok() {
  local step="$1" path="$2"
  case "$CODE" in
    2*) ;;
    *)
      echo "✗ $step 失败：$path" >&2
      echo "  HTTP $CODE" >&2
      echo "  响应体：" >&2
      cat "$BODY" >&2; echo >&2
      exit 1
      ;;
  esac
  if ! grep -q '"code"[[:space:]]*:[[:space:]]*200' "$BODY"; then
    echo "✗ $step 业务码不是 200：$path" >&2
    cat "$BODY" >&2; echo >&2
    exit 1
  fi
}

echo "base=$BASE projectId=$PROJECT_ID userId=$TEST_USER_ID date=$TODAY"

# ① 事实汇入：把最近 24 小时有变动的会议/邮件/工作项/大事儿抄进 project_fact。
#    预期 200，data 为「新增或更新的条数」（0 也表示链路通，只是这段时间没变动）。
request POST "/internal/project-context/$PROJECT_ID/ingest?hours=24"
expect_ok "① 事实汇入" "/internal/project-context/$PROJECT_ID/ingest?hours=24"
CHANGED="$(sed -n 's/.*"data"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$BODY" | head -n 1)"
echo "✓ ① 汇入完成，changed=${CHANGED:-?}"

# ② 事实读取：agent-loop 夜里就是用这个端点/工具拿证据的。
#    预期 200，data 为数组（可能为空），limit 生效。
request GET "/internal/project-context/$PROJECT_ID/facts?limit=5"
expect_ok "② 事实读取" "/internal/project-context/$PROJECT_ID/facts?limit=5"
if ! grep -q '"data"[[:space:]]*:[[:space:]]*\[' "$BODY"; then
  echo "✗ ② 的 data 不是数组：" >&2
  cat "$BODY" >&2; echo >&2
  exit 1
fi
FACT_COUNT="$(grep -o '"sourceType"' "$BODY" | wc -l | tr -d ' ')"
echo "✓ ② 读到 ${FACT_COUNT} 条事实（limit=5）"

# ③ 写晨报：按 (userId, briefDate=今天, projectId) 写入/覆盖一条测试晨报。
#    生产环境请删掉这行测试数据（user_id/brief_date/project_id 已知）。
#    写入 date=今天，正是 07:30 推送要读的那一行——跑之前确认不会推给真人（见下方提示）。
BRIEF_JSON="{\"userId\":$TEST_USER_ID,\"projectId\":$PROJECT_ID,\"briefDate\":\"$TODAY\",\"content\":\"$(printf '%s' "$CONTENT" | json_escape)\",\"sourceTask\":\"smoke\"}"
request POST "/internal/project-context/briefs" "$BRIEF_JSON"
expect_ok "③ 写晨报" "/internal/project-context/briefs"
BRIEF_ID="$(sed -n 's/.*"data"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$BODY" | head -n 1)"
if [ -z "$BRIEF_ID" ]; then
  echo "✗ ③ 没有返回晨报 id：" >&2
  cat "$BODY" >&2; echo >&2
  exit 1
fi
echo "✓ ③ 晨报已写入，id=${BRIEF_ID}，brief_date=${TODAY}"
echo "  ! 这条测试晨报的 brief_date=今天且 delivered_at 为空——若在 07:30 前跑且推送开关开着，会被真的推到企微。"
echo "    可选：PROJECT_BRIEF_PUSH_ENABLED=false 重启后端后再跑；或跑完立刻 DELETE FROM ai_brief WHERE id=$BRIEF_ID;"

# ④ 读晨报：/api/ai-briefs 是登录态业务接口（@RequestAttribute userId），
#    内部令牌进不来，需要带上真实 access token；没给就跳过并给出跳过方式。
if [ -z "$JWT" ]; then
  echo "SKIP ④ 读晨报：未提供 HUJI_JWT。"
  echo "     取 token 的方式：curl -sS -X POST '$BASE/api/auth/login' \\"
  echo "       -H 'Content-Type: application/json' \\"
  echo "       -d '{\"username\":\"admin\",\"password\":\"123456\"}'   # 换成试点成员账号"
  echo "     然后 HUJI_JWT=<data.token> 重跑本脚本；或直接用内部读法/DB 确认："
  echo "       SELECT id,brief_date,project_id,content FROM ai_brief WHERE user_id=$TEST_USER_ID AND brief_date='$TODAY';"
  echo "已完成 3/4 步（第 4 步需登录态）。"
  exit 0
fi

request GET "/api/ai-briefs?date=$TODAY" "" "Authorization: Bearer $JWT"
expect_ok "④ 读晨报" "/api/ai-briefs?date=$TODAY"
if ! grep -q "$MARKER" "$BODY"; then
  echo "✗ ④ 登录态里读不到刚写的晨报（id=${BRIEF_ID}）：" >&2
  cat "$BODY" >&2; echo >&2
  exit 1
fi
echo "✓ ④ 读回成功：今日晨报含刚写入的测试内容"

echo "全部通过（4/4）。清理测试数据：DELETE FROM ai_brief WHERE id=$BRIEF_ID;"
