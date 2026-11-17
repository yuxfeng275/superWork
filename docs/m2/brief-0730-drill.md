# 07:30 晨报定时投递演练（2026-11-18）

为验证 `ProjectBriefPushScheduler` 在 07:30 真实触发（而不是手动调接口），
把调度时区临时覆盖为 `Pacific/Tongatapu`（UTC+13），使测试窗口内的本地时间正好是 07:30。

| 项 | 值 |
|---|---|
| 调度时区（测试） | `Pacific/Tongatapu`，本地 07:30 = 北京 02:30 |
| 触发日志 | `2026-11-18 02:30:00.026 [sch-4] INFO ProjectBriefPushScheduler - 晨报已推送 1 人` |
| 送达记录 | `ai_brief` id 16（e2e_admin，projectId=0），`delivered_at=2026-11-18 02:30:00`，`delivery_channel=IN_APP` |
| 生产时区 | 演练后已恢复 `Asia/Shanghai`，生产 cron 仍为 `0 30 7 * * MON-FRI` |
| 说明 | 站外通道未配置，按设计记站内送达；不是外发替代 |

该演练证明定时器在 07:30 会自行触发并写入送达记录；手动触发只用于验证接口链路。
