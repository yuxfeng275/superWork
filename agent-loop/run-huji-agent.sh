#!/bin/sh
# agent-autoloop command 通道的启动壳：只负责把环境变量固定下来，再交给 sidecar-agent.mjs。
# 修改 HUJI_* 三个值后即可被 rules.json 的 program 指向本脚本。
set -eu

HUJI_BASE_URL="${HUJI_BASE_URL:-http://127.0.0.1:8080}"
HUJI_TOKEN="${HUJI_TOKEN:?缺少 HUJI_TOKEN（合拍的 project-context.token）}"
HUJI_AGENT_USER_ID="${HUJI_AGENT_USER_ID:?缺少 HUJI_AGENT_USER_ID（试点成员 userId）}"
HUJI_PROJECT_ID="${HUJI_PROJECT_ID:?缺少 HUJI_PROJECT_ID（目标项目 id）}"

export HUJI_BASE_URL HUJI_TOKEN HUJI_AGENT_USER_ID HUJI_PROJECT_ID
exec node "$(dirname "$0")/sidecar-agent.mjs" "$@"
