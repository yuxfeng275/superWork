# M2 夜间补丁 runner

`nightly.sh` 是 agent-autoloop 的 command 通道脚本：

- 队列文件默认 `/home/openclaw/m2/queue/current.md`；**为空时直接退出**，不启动 dsh、不消耗 token。
- 有任务时在隔离 clone `/home/openclaw/repos/superwork-m2` 的 `m2/<时间戳>` 分支上运行
  `dsh headless --permission-mode workspace-write --approval-policy never`，
  要求 dsh 做最小改动、跑相关测试、提交；**不 push、不碰 master**。
- 产出 patch 到 `/home/openclaw/m2/out/<branch>.patch`，dsh 日志同目录。
- 队列首行可写 `fact: <id>` 作为本条任务的证据；runner 会读取并传给 `report.py`。用 `enqueue.py --fact N --task "..."` 入队。
- 模型凭据只从 `/home/openclaw/huji-agent/dsh.env`（600）读取，不写仓库。

接入 autoloop（示例，按本机 rules.json 的 schema 调整）：

```json
{
  "name": "M2 夜间补丁",
  "when": { "cron": "0 23 * * 1-5" },
  "to": "m2",
  "cwd": "/home/openclaw/repos/superwork-m2",
  "prompt": "运行 M2 队列"
}
```

agent `m2` 用 `use:"command"` 指向 `tools/m2/nightly.sh`。队列空时是 no-op；有任务才调用 dsh。
产出 patch 后，若 `m2.env` 配了 `M2_ADMIN_PASSWORD`，`report.py` 会调用 `/api/ai-artifacts`
自动建 FIX_NOTE 并送审（证据取 `M2_FACT_ID`）；不会自动部署。
