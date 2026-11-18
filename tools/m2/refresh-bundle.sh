#!/bin/sh
# 生成/推送 M2 runner 使用的 master bundle，避免隔离 clone 停在旧提交。
set -eu
OUT="${1:-/tmp/superwork-master.bundle}"
git bundle create "$OUT" master >/dev/null
git bundle list-heads "$OUT" | grep -q "refs/heads/master" || { echo "bundle 无 master 引用" >&2; exit 2; }
echo "bundle=$OUT"
if [ -n "${M2_HOST:-}" ]; then
  DEST="${M2_REMOTE_PATH:-/home/openclaw/m2/superwork-master.bundle}"
  scp -q "$OUT" "${M2_HOST}:${DEST}"
  echo "pushed=${M2_HOST}:${DEST}"
fi
