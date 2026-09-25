# 日程中心（会议 × 企微日程合并视图）

> 上线：2026-09-18（master `b57b7e7`，V84 菜单）。目标：把**会议模块的本地会议**与**企微日程**合并成一个可用的日历/列表视图。
>
> 存档说明（2026-09-25）：旧 Vue 前端已下线删除，文中「旧 Vue」「两套前端」相关描述仅作历史记录，当前唯一前端为 frontend-pro。

## 一、数据来源与边界

| 来源 | 说明 | 可见范围 |
| --- | --- | --- |
| `MEETING` 本地会议 | 会议模块的录音/转写/纪要记录（`meeting` 表） | 按**上传人**（本人）过滤，与会议模块 v1 口径一致 |
| `WECOM_SCHEDULE` 企微日程 | wecom-cli `calendar schedules list`（机器人授权身份可见，含共享日历） | 授权身份可见范围 |

**本地会议只有日期粒度**（`meeting_date` 无起始时刻）→ 日历中作为**全天事件**渲染，点击跳 `/meetings/{id}` 查看转写与纪要。

## 二、接口

```
GET /api/schedule/events?from=YYYY-MM-DD&to=YYYY-MM-DD&sources=MEETING,WECOM_SCHEDULE
权限：schedule:view（V84 授权 DIRECTOR / DEPUTY_DIRECTOR / BUSINESS_OWNER / EFFECTIVENESS_OWNER / BU_ADMIN）
→ data: { events: [...], hints: string[], rangeStart, rangeEnd }
```

事件字段：`id / source / title / start / end / allDay / location / organizer / calendarName / participants[] / status / meetingId / scheduleId / meetingCode / meetingLink / recurring / description`。

- 时间为**墙上时间**字符串 `yyyy-MM-dd HH:mm:ss`（前端必须按格式解析，禁止 `new Date(str)`）。
- `hints` 携带企微侧提示（未授权、窗口越界等，含 markdown 链接），**不阻断**本地会议展示。

## 三、周期日程展开（关键实现）

企微对周期日程返回的是**系列**（`begin_time` 为系列首次时间），日历要按天展示必须自行展开。
实现见 `integration/ScheduleRecurrence`（纯函数，可单测）：

| 规则字段 | 处理 |
| --- | --- |
| `repeat_type` | daily / work_day（周一至周五）/ weekly / monthly / monthly_on_the_nth_day / yearly / yearly_on_the_nth_day |
| `repeat_interval` | 间隔（周/月/年/天） |
| `repeat_day_of_week` | 自定义每周多天（MO..SU），仅 `is_custom` 时生效 |
| `repeat_day_of_month` / `repeat_month_of_year` | 每月第几天 / 每年哪几月 |
| `repeat_time` | 次数上限（0 = 不限） |
| `repeat_until` | 结束时间 |
| `exception[].begin_time` | 例外场次 → 跳过该场（改期/取消） |
| 无效日期（如 2/30） | 跳过，与主流日历一致 |

安全阀：单条日程最多展开 400 场、迭代 5000 次（防「永不结束 + 极早起始」的无限循环）。

## 四、企微窗口限制

企微日程**只能查询当天前后 30 天**（越界返回 `90747`）。后端按 `当天 ±29 天` 钳制查询窗口（留一天余量），
超出部分在 `hints` 里说明「已按 X ~ Y 取数」；窗口完全越界时返回空事件 + 提示，不报错。

## 五、前端

- **Pro（`/schedule`）**：`pages/schedule/`，月网格（周一为起始、每格 ≤3 条 chip + 「+N」、来源分色、全天/周期标记）+ 右侧当日面板；列表模式按天分组时间轴；来源筛选、月份导航、刷新；企微事件抽屉（含线上会议号/入会链接）；hints 渲染为可点外链。
- **旧 Vue（`/schedule`）**：`views/ScheduleView.vue` 同口径；本地会议走抽屉（旧前端无会议模块，不跳转，抽屉内说明指向会议模块）。
- 会议管理页新增「日历视图」入口跳 `/schedule`。
- 旧 Vue 侧边栏支持「**已注册路由的单页一级菜单**」（此前只渲染有子菜单的根节点，导致 `/schedule` 不显示）。

## 六、验证（2026-09-18，真实数据）

| 项 | 结果 |
| --- | --- |
| 后端单测 | 458 全绿（含 10 条周期展开用例：每周/双周/自定义多天/工作日/每月无效日期/次数上限/例外/repeat_until/展开上限） |
| 真实数据 | 2026-09 日历视图 120 条（网格 8/31–10/4）、列表模式 108 条（= 本地会议 4 + 企微日程展开 104） |
| 时区 | 与 API 返回值逐条一致（09:30/10:30/14:00…），无偏移 |
| 交互 | 两套前端模式切换/来源筛选/月份导航/刷新/空月份提示；Pro 本地会议跳 `/meetings/{id}`；企微抽屉展示会议号与入会链接；hints 链接 `target=_blank rel=noopener noreferrer` |
| 边界 | 越界窗口（10/15–11/15）→ 钳制到 10/15–10/17 并在 hints 说明，返回 10 条；空区间返回 0 条 |

## 七、已移除的能力

**企微会议（原 `WECOM_MEETING` 数据源）**：企微「会议」品类授权已确认无法获取，故整条线移除——后端不再请求 `meeting list`、日程页不再有该来源与筛选、相关 AI 工具（`wecom_list_meetings` / `wecom_meeting_detail` / `wecom_meeting_transcript`）与 REST 端点、品类探针一并删除。如需恢复，参考 git 历史 `fb5cd59^`。

## 八、待办

- 如需**团队日历**（看他人会议），需放开本地会议的可见范围（当前为本人）——属产品决策。
