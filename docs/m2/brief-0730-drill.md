# 07:30 晨报定时投递演练（2026-11-18）

为验证 `ProjectBriefPushScheduler` 在 **本地 07:30** 自行触发（不是手动调接口），
把调度时区临时覆盖为自定义偏移，使测试窗口内的本地时间正好是 07:30；生产时区随后恢复 `Asia/Shanghai`。

| 演练 | 时区（临时） | 触发日志 | 送达记录 |
|---|---|---|---|
| 皇家项目真实晨报 | `GMT+12:49`（本地 07:30 = 北京 02:41） | `2026-11-18 02:41:00 [sch-3] 晨报已推送 1 人` | `ai_brief` id 13（user 9，project 1，2026-11-18），`delivered_at=02:41:00`，`IN_APP` |
| 测试账号晨报 | `Pacific/Tongatapu`（本地 07:30 = 北京 02:30） | `2026-11-18 02:30:00 [sch-4] 晨报已推送 1 人` | `ai_brief` id 16（e2e_admin），`delivered_at=02:30:00`，`IN_APP` |
| 生产真正触发 | `Asia/Shanghai` | `2026-11-18 07:30:00` 日志「晨报已推送 1 人」 | brief 13（user 9，皇家 project 1）`delivered_at=07:30:00`，`IN_APP` |
| 生产时区 | `Asia/Shanghai` | cron `0 30 7 * * MON-FRI` | 演练后已恢复 |

说明：站外企微/Bark 未配置，按设计记**站内送达**（IN_APP），不是外发替代；
手动触发只用于验证接口链路，本页两次送达均由 `ProjectBriefPushScheduler` 定时触发并留下日志。
