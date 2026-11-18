#!/bin/sh
# M2 夜间补丁 runner（给 agent-autoloop 的 command 通道用）
#
# 队列为空时直接退出，不启动 dsh、不消耗 token；有任务时在隔离 clone 里
# 让 dsh 改代码、跑测试、提交分支，并输出 patch。绝不 push、绝不碰主工作区。
set -eu

QUEUE_FILE="${M2_QUEUE_FILE:-/home/openclaw/m2/queue/current.md}"
OUT_DIR="${M2_OUT_DIR:-/home/openclaw/m2/out}"
REPO_DIR="${M2_REPO:-/home/openclaw/repos/superwork-m2}"
KEY_FILE="${M2_KEY_FILE:-/home/openclaw/huji-agent/dsh.env}"
ENV_FILE="${M2_ENV_FILE:-/home/openclaw/huji-agent/m2.env}"
DSH_NODE="${M2_DSH_NODE:-/home/openclaw/node24/bin/node}"
DSH_JS="${M2_DSH_JS:-/home/openclaw/node_modules/@deepseek-ai/dsh/lib/bin.js}"
DSH_HOME_DIR="${DSH_HOME:-/home/openclaw/.dsh}"
MASTER_BUNDLE="${M2_MASTER_BUNDLE:-/home/openclaw/m2/superwork-master.bundle}"

if [ ! -s "$QUEUE_FILE" ]; then
  echo "M2 队列为空（${QUEUE_FILE}），跳过"
  exit 0
fi
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi
if [ -f "$KEY_FILE" ]; then
  # shellcheck disable=SC1090
  . "$KEY_FILE"
fi
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "缺少 DEEPSEEK_API_KEY（${KEY_FILE}），拒绝启动 dsh" >&2
  exit 2
fi
if [ ! -d "$REPO_DIR/.git" ]; then
  echo "M2 仓库不存在或不是 git 工作区：$REPO_DIR" >&2
  exit 3
fi

mkdir -p "$OUT_DIR"
FACT_FROM_QUEUE="$(sed -n 's/^fact:[[:space:]]*//p' "$QUEUE_FILE" | head -1)"
if [ -n "$FACT_FROM_QUEUE" ]; then export M2_FACT_ID="$FACT_FROM_QUEUE"; fi
TASK="$(sed '1{/^fact:[[:space:]]*/d;}' "$QUEUE_FILE")"
BRANCH="m2/$(date +%Y%m%d-%H%M%S)"
LOG="$OUT_DIR/$(echo "$BRANCH" | tr '/' '-').log"
PATCH="$OUT_DIR/$(echo "$BRANCH" | tr '/' '-').patch"

cd "$REPO_DIR"
# 隔离 clone 不会自己更新；每次任务前从本地 bundle 同步最新 master。
# fetch 到 FETCH_HEAD 再建 m2-base，避免 clone 脏时 fetch 到当前分支失败后继续用旧代码。
if [ -f "$MASTER_BUNDLE" ]; then
  git fetch "$MASTER_BUNDLE" master >/dev/null
  git checkout -B m2-base FETCH_HEAD >/dev/null
else
  git checkout -B m2-base master >/dev/null
fi
git reset --hard m2-base >/dev/null
git clean -fd >/dev/null
git checkout -b "$BRANCH" >/dev/null

PROMPT="M2 夜间补丁任务。请只在这个隔离 clone 里工作：阅读任务、做最小改动、运行相关测试、在当前分支 $BRANCH 提交；不要 push、不要切回/修改 master、不要改仓库外文件。任务如下：
$TASK"

set +e
DSH_HOME="$DSH_HOME_DIR" DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
  "$DSH_NODE" "$DSH_JS" headless --json --permission-mode workspace-write \
  --approval-policy never "$PROMPT" > "$LOG" 2>&1
RC=$?
set -e

if ! git diff --quiet m2-base..HEAD 2>/dev/null; then
  git format-patch -1 --stdout > "$PATCH" 2>/dev/null || git diff m2-base..HEAD > "$PATCH"
  echo "M2 产出：branch=$BRANCH patch=$PATCH log=$LOG dsh_rc=$RC"
else
  echo "M2 无代码变更：branch=$BRANCH log=$LOG dsh_rc=$RC"
fi

# 有 patch 且配置了管理员口令时，自动建 FIX_NOTE 并送审
if [ -s "$PATCH" ] && [ -n "${M2_ADMIN_PASSWORD:-}" ]; then
  python3 "$(dirname "$0")/report.py" \
    --base "${M2_BASE_URL:-http://127.0.0.1:18081}" \
    --user "${M2_ADMIN_USER:-admin}" \
    --password "$M2_ADMIN_PASSWORD" \
    --project "${M2_PROJECT_ID:-1}" \
    --fact "${M2_FACT_ID:-71}" \
    --branch "$BRANCH" --patch "$PATCH" --task "$TASK" \
    || echo "M2 制品自动登记失败，patch 仍在 ${PATCH}" >&2
fi
