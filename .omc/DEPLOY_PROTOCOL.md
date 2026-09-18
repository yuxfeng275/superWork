# 部署协调约定（所有 agent 必须遵守）

> 更新：2026-09-04；维护者：master 分支工作 agent
> 适用范围：`superWork-claude-sp` 主工作空间 + `superWork-sp-agent`/`bigwork` 等 worktree 的所有 agent

## 铁律

1. **先合并，后部署**：任何部署到 241 的代码必须已经合入 `master` 分支。
   禁止直接从 feature/worktree 分支构建部署。241 上的 jar/dist 必须与 origin/master 一致。

2. **master 是唯一部署源**：部署产物（jar/dist）只从 `master` 分支的 `backend/target/management-1.0.0.jar`
   、`frontend/dist/`、`frontend-pro/dist/` 构建。worktree（bigwork/aiagent 等）只负责开发与自测。

3. **合并前必须同步**：merge 到 master 前先 `git fetch`，确认没有其他 agent 刚推过提交；
   merge 后 `git push origin master` 成功才算完成合并。

4. **部署后登记**：部署完成立即更新：
   - `241:/home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT` = 部署的 commit 短 hash
   - 本文件的「部署记录」小节

5. **注意并行 agent**：`aiagent` 分支的 agent 在同步工作。合并时如遇本地未提交改动冲突，
   先 stash（带说明性消息），merge 后立即 `stash pop` 恢复；绝不能丢弃别人的工作。

## 部署标准流程

```
# 1. 在 feature/worktree 分支提交并验证
# 2. 在 master 工作空间（superWork-claude-sp）：
git fetch origin
git merge <branch> --no-edit        # 或直接在 master 上提交
git push origin master
# 3. 构建 + 同步 + 重建容器（参照 docs/deployment.md）：
rsync jar/dist → server-241
ssh server-241 'cd docker && docker compose -f docker-compose.241.yml up -d --build frontend-pro && docker restart superwork-bu-nginx'
# 241 入口：frontend-pro :18080，旧 Vue :18088，backend :18081
# 4. 登记部署
```

## 部署记录

| 时间 | commit | 内容 | 操作者 |
|---|---|---|---|
| 2026-09-04 | 3ada6af | 大事儿周会左右卡片加宽 | master agent |
| 2026-09-04 | e87d84c | 未关联项目快速筛选修复 | master agent |
| 2026-09-04 | c2d1593 | 短信合同归精准线 + 非 full 线业务线优先 | master agent |
| 2026-09-04 | 003e4f1 | 合同总额新增收款月口径（工时系统对齐 4,738,115.91） | master agent |

| 2026-09-15 | 6b658ac | 系统菜单管理接口：全量菜单、创建/更新/删除、排序 | master agent |
| 2026-09-15 | 34d0cc7 | 菜单更新保留原有 icon，避免编辑其他字段时图标丢失 | master agent |
| 2026-09-16 | d0a1700 | 后端（V76-V78 数据同步中心/OA采集）+ 新前端 frontend-pro 并行上线（:18084，旧前端 :18080 保留共兜底，共用后端 :18081）；备份 deploy-backups/20260916-142813 | master agent |
| 2026-09-16 | c478587 | frontend-pro：大事儿列表新增周进展入口；周报弹窗改为周会卡片样式+右侧历史进度（仅前端，backend 未变） | master agent |
| 2026-09-16 | b65db78 | frontend-pro：大事儿管理快速筛选栏 276→216px；列表操作列「详情/删除」并入「更多」下拉（四列改 196px，≤1420px 两行栅格），计划时间不再被操作按钮压盖；周进展弹窗按视口定高改 2×2 表单，单屏免滚动（仅前端，backend 未变）；备份 deploy-backups/20260916-233419 | normal-modify agent |
| 2026-09-17 | f3f43d4 | frontend-pro：修复「系统>数据集成中心 /system/sync」未在 routes.ts 注册导致点菜单被 catch-all 重定向回首页（并补 management 权限路径与 CloudSync 菜单图标映射）；登录页品牌由「BU」色块改为合拍 logo（public/logo.png），与顶栏一致；备份 deploy-backups/20260916-235311 | normal-modify agent |
| 2026-09-17 | 8890027 | **连接器收口（Connector Hub）**：连接配置统一到 `ai_connector` 注册表与「系统管理→系统配置→连接器管理」单一入口（`/system/connectors`）；V79 迁移 + 启动搬迁器把云效/工时/OA 单行配置表与 ai-connector/email-integration/ai-agent/oa-vreport/weekly-report(yuque.*) 配置组搬入连接器并隐藏源配置（规则 v2：单行配置表优先，标记带版本可重跑）；注册表 API 迁到 `/api/connectors`，退役 `/api/{yunxiao,worktime,seeyon-oa}/config|connection-test` 与 `/api/system/configs/*/test`；两套前端清理散落表单（KPI 工时配置、驾驶舱云效配置、配置管理测试按钮）改为只读+跳转；OA 连接器按现状停用（致远网关 401，待加 IP 白名单）；备份 deploy-backups/20260916-161151 | system-config agent |
| 2026-09-17 | a62aed2 | frontend-pro（大事儿管理）：周进展弹窗标题改为具体事项名、去掉 WEEKLY UPDATE 头部与内容区重复事项名；进度展示加宽（弹窗进度条铺满主栏、列表「当前进度」条整列铺满 66→168px 且去重百分比）、弹窗主体高度上限 520px（1440×700 也免滚动）；列表操作列改「周进展 / 详情 / 更多（编辑、删除）」；周进展不再连带打开详情抽屉（独立 weeklyMatter 状态）。本次为 8890027 之后的 master 重建，含连接器收口前端（/system/connectors）；备份 deploy-backups/20260917-095423，已按提醒重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | normal-modify agent |
| 2026-09-17 | 2a7e8a8 | frontend-pro（大事儿管理·周会与列表）：①周会演示状态标签+进度条平铺到「本周进展」卡标题后（去掉顶部独立状态标签与独立进度条）；②周会演示改整屏布局：deck 按视口定高 calc(100vh-84px)，卡片区内部滚动、标题/进度/操作/缩略图常驻可见，收回演示页内容区与页面留白（16+24→8+0），编辑态文本框 4 行→3/2/2/2 行，≤1420/≤1180 分级收紧卡片与字号（1440×900、1366×768 实测卡片区 hidden=0）；③列表进度条上下留白 4→11/12px 且对称、条高 6→8px、标签弱化数值强化、未更新用琥珀色；④周会快速筛选「项目/负责人」改为系统一致的默认尺寸 block Segmented（24→32px 全宽）；⑤周会分组头像由 antd 默认灰改为按分组键散列的品牌渐变（负责人圆形/项目圆角方形）。备份 deploy-backups/20260917-115837，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | normal-modify agent |
| 2026-09-17 | 9c69e84 | frontend-pro：周会演示标题行右侧展示项目归属与负责人、进度条增加上下间距（仅前端） | master agent |
| 2026-09-17 | fdd0f22 | frontend-pro（报价策略/报价单）：查询区与列表补齐 16px 间距（筛选卡片 `margin-bottom: 16px`，与客户/商机/项目页一致）；仅前端。备份 deploy-backups/20260917-141432，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |

| 2026-09-17 | 1cd5ad6 | frontend-pro（大事儿管理·周会演示）：①状态与进度改为「本周进展」标题行内编辑（Select 116px + 进度条 + InputNumber 96px），非编辑态该行仍为 状态标签+进度条+百分比；②删除底部状态/进度选择器与快捷百分比按钮，底部仅留 暂存草稿/保存并下一项/取消（查看态 查看详情/更新周报）；③缩略图 sw-presentation-thumbs 移出舞台卡片，放入新增 .sw-presentation-main（舞台+缩略图）列，单行横向滚动 28px 圆点；卡片区 1440×900/1366×768/1280×720 实测 hidden=0；④圆形分组头像恢复正圆（仅圆角方形分组加 8px 圆角）。本次为 10cd441（模型管理抽离）之后的 master 合并重建；备份 deploy-backups/20260917-143743，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | normal-modify agent |
| 2026-09-17 | 10cd441 | **模型管理抽离**：AI 模型独立成「系统管理→系统配置→模型管理」（`/system/models`，`ai_model` 表 V80：模型名/助手可用/摘要使用/默认/启停），连接器只管连接（地址/凭据/启停/测试）；模型 API `/api/ai/models`，助手下拉改由模型注册表驱动（同名模型自动补提供方名）；摘要按排序取「摘要使用」模型不被助手默认模型抢占；两套前端新增模型页，连接器页移除模型字段；新增 OA/语雀连通性确认文档 docs/oa-yuque-readiness.md；备份 deploy-backups/20260917-061125 | system-config agent |
| 2026-09-17 | 1e42038 | frontend-pro：统一列表页统计区/查询区/列表 16px 间距（共享 `.sw-stat-row` / `.sw-filter-card` / 页面级 Alert）；覆盖业务线、项目、客户、用户、数据集成、驾驶舱、KPI 等同类页；仅前端。备份 deploy-backups/20260917-150200，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |

| 2026-09-17 | 8a7cb4c | 语雀连接器测试修正：官方 yuque-mcp-server 证实 MCP 官方形态为本地 stdio + REST v2 同链路，MCP 网关 403 时按 REST v2 判定通过（prod 已验证 last_test=SUCCESS，REST v2 通道） | system-config agent |
| 2026-09-17 | 204ca31 | frontend-pro：补齐「系统→权限管理」`/system/permissions` 路由与只读权限列表页，避免菜单入口被 catch-all 打回首页；仅前端。备份 deploy-backups/20260917-151224，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | d03e8b0 | frontend-pro：查询卡片/工具栏筛选项换行后补 12px 行间距（共享 `.sw-filter-card` inline 表单与 Space，任务工具栏、大事儿查询区同步）；仅前端，含 71fae6b。备份 deploy-backups/20260917-151809，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | 23ef096 | frontend-pro（大事儿管理·周会演示）：①卡片区取消底部留白——`align-content: stretch` + 卡片 `height:100%`、卡体 flex 列让文本框撑满（1440×900 / 1366×768 实测底部留白 0，卡片高 226/154/154/154 与 198/126/126/126，文本框 118/96/96/96 与 90/68/68/68）；②「本周进展」状态由下拉改为平铺 Segmented（6 个状态全可见，与系统控件一致），查看态仍为单标签；③进度快捷百分比 0/25/50/75/90/100% 从底部工具条移入本周进展卡头第二行（与进度条、进度输入同行右对齐）。备份 deploy-backups/20260917-152236，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | normal-modify agent |
| 2026-09-17 | 0537bb2 | frontend-pro（大事儿管理·周会演示）：①状态分色表达——新增 STATUS_TONES（未开始灰 #8c8c8c / 推进中蓝 #1677ff / 有风险橙 #fa8c16 / 已阻塞红 #ff4d4f / 已完成绿 #52c41a / 已暂停紫 #722ed1），同时作用于平铺状态选项色点、查看态状态标签、进度条 strokeColor（实测 6 色点 + 条色随状态）；②状态与进度之间加 1px 竖划线（绑定进度组 `border-inline-start` + 14px 内边距），窄屏进度换行后仍为该组前导分隔；③底部操作按钮整行居中（实测按钮中点与卡片中点一致）；④收紧头部固定宽度（状态项 6px 内边距、快捷百分比 36px、进度组 160px 起）。备份 deploy-backups/20260917-164854，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | normal-modify agent |
| 2026-09-17 | 04febb6 | frontend-pro：商机工时弹窗右侧补历史记录（对齐跟进弹窗左右分栏）；商机列表/看板/详情新增报价入口，复用报价向导并预填客户与商机；仅前端。备份 deploy-backups/20260917-155907，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | 08891d6 | frontend-pro：工作台按现有模块重做，并行拉取需求/任务/缺陷/大事儿/商机/报价，补齐可跳转 KPI、待办队列、今日关注和功能入口；仅前端，含 9f03f36。备份 deploy-backups/20260917-081538，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | eb1a6fb | frontend-pro：去掉 Input/Select/DatePicker 点击后多出来的额外 outline 描边（全局样式叠在 Ant Design 焦点边框上）；仅前端，含 42dbe32。备份 deploy-backups/20260917-082456，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | f1acc66 | frontend-pro：周报中心概览进行中为 0 停止转圈，已确认/已发布按状态拆分计数；仅前端，含 80075d9。备份 deploy-backups/20260917-084024，已重启 nginx 并核对 :18080=旧 Vue / :18084=frontend-pro | ui-fix agent |
| 2026-09-17 | 67ea82b | 241 默认入口切换：frontend-pro 改 :18080，旧 Vue 改 :18088，废弃 :18084；同步 nginx/compose 与部署文档。备份 deploy-backups/20260917-085229，已 recreate nginx 并核对 :18080=frontend-pro / :18088=旧 Vue / :18084 关闭 | ui-fix agent |
| 2026-09-17 | 17ec31f | frontend-pro：侧栏工作台改 Appstore 图标与首页房子区分；新建/编辑大事儿关联项目必填；仅前端，含 19ce95b。备份 deploy-backups/20260917-090403，已重启 nginx 并核对 :18080=frontend-pro / :18088=旧 Vue | ui-fix agent |
| 2026-09-17 | 3e03959 | frontend-pro：停止从 app.tsx 导出 mapServerMenu，修复 Umi `invalid key mapServerMenu` 白屏；仅前端，含 47b812c。备份 deploy-backups/20260917-091316，已重启 nginx 并核对 :18080=frontend-pro / umi.4e2731a9.js 200 | ui-fix agent |
| 2026-09-17 | ace6d8b | frontend-pro：侧栏工作台强制 Appstore（服务端 HomeFilled 不再覆盖）；周进展弹窗左侧对齐周会卡片并保留右侧历史；周起始日统一 ISO 周一，修复新增周进度 400。仅前端，含 f0e1245。备份 deploy-backups/20260917-174243，已重启 nginx 并核对 :18080=frontend-pro / umi.6823ff8a.js 200 / 旧 umi.4e2731a9.js 404 / :18088=旧 Vue | ui-fix agent |
| 2026-09-18 | 2045ea2 | frontend-pro：周进展弹窗标题改为「MM/DD - MM/DD 周进展」，区间取当周周一到周日；仅前端，含 327e087。备份 deploy-backups/20260918-091256，已重启 nginx 并核对 :18080=frontend-pro / umi.fbec6043.js 200 / p__key-matters__index.575c4404.async.js 200 / :18088=旧 Vue | ui-fix agent |
| 2026-09-18 | b5ea6f2 | frontend-pro：周进展弹窗去掉快捷百分比，进度条改为可拖拽 Slider，右侧保留数值输入；仅前端。备份 deploy-backups/20260918-091625，已重启 nginx 并核对 :18080=frontend-pro / umi.430f8b8c.js 200 / p__key-matters__index.d8d619d9.async.js 200 / :18088=旧 Vue | ui-fix agent |
| 2026-09-18 | c56a19e | OA 验证码登录改用 A8 V9 网页表单字段 `login_password`（原 `login_password1` 触发 loginerror=11）；错误码补 11=参数不完整；前端失败展示后端原文并展开 JSESSIONID 兜底。backend + frontend-pro，含 31647b7。备份 deploy-backups/20260918-094431，已重启 nginx 并核对 :18080=frontend-pro / umi.0f21f4fa.js 200 / p__oa-affairs__index.b158f5ac.async.js 200 / backend health 200 | ui-fix agent |

| 2026-09-17 | eb7576b | **会议模块上线（录音→ASR 匿名分人→闪记式图文总结→待办转化）**：V81 迁移（meeting/meeting_speaker/meeting_todo 三表 + `task.requirement_id` 放开 NULL + meeting 配置组 + `/meetings` 菜单与五角色授权）；backend 13 个 `/api/meetings` 端点（转写校正/说话人命名/总结重跑/待办转化/音频回放）；frontend-pro `/meetings` 列表+详情（关键词 Tag、发言统计环形图、章节速览 Timeline、决策/风险、待办草稿）；新增自托管 **asr-worker**（FunASR + ffmpeg，内网 8790 不发布宿主端口，模型缓存 ~2.1GB 于 `docker/asr-models`）。验收：30s 双人样本 9 段转写、SPEAKER_00/01 正确分人、章节/关键词/每人要点/风险齐备、待办转任务 `requirement_id=NULL` 成功；meeting 组已启用且 `meeting.asr.base-url=http://asr-worker:8790`。备份 deploy-backups/superwork-meeting-20260917-164333；前置修复：宿主 `docker/data/meetings` 属主改 999、asr-worker 构建加镜像源/wheelhouse 参数（eb7576b） | voice agent |

| 2026-09-17 | d071f8d | **OA 待办审批台 + 网页会话通道**：REST 被拦时的落地实现——管理员粘贴 JSESSIONID 一次授权（隐藏敏感配置项加密持久化），ajax.do/doProjection 取待办/已办（与门户同一数据源），批量审批走详情页自发现动作；新页 /oa-affairs（V82 菜单，注意 V81 与会议模块撞号已改 V82）；两套前端页已上线 | system-config agent |
| 2026-09-17 | b7f5a0e | **OA 自助授权三档 + 会话保活**：自动登录（免验证码时）/ 验证码登录（挑战图 5 分钟有效、取图 Cookie 并入挑战）/ 粘贴 JSESSIONID 兜底；每 10 分钟保活会话；登录失败透出 LoginError 码（9=验证码、1=账号密码）；待验证项：待办/已办真实取数、审批动作自发现、vReport 导出地址 | system-config agent |
| 2026-09-18 | 55896f3 | ä¼å¾®è½ååç±»è¡¥å¨ï¼853006ï¼è½åæªå¯¹ä¼ä¸å¼æ¾ï¼ååç¶æä¸ç®¡çåå¼éæç¤ºï¼11 æ¡å·¥å·è·¯å¾å¨é¨å¯¹çå® CLI éªè¯åæ°æé ï¼æ ç¨æ³éè¯¯ï¼åæ­£ç¡®å°è¾¾æææ ¡éªï¼ | system-config agent |
| 2026-09-18 | 59e4a97 | **企微品类授权到位后的真实数据回归**：修复 4 处真实数据暴露的问题——① 待办 `creator`/`deadline` 实为对象（此前解析为空，现正确显示创建人/截止/提醒/来源）；② 创建待办 `deadline` 需提交 `{type,value}` 对象（原字符串被 10003 拒绝，创建闭环现已打通并清理测试数据）；③ 品类探针改用相对时间窗（日程限 ±30 天，原固定窗口误报 ERROR）；④ 消息品类探针改为「发送」口径（原 sessions 口径把「需企业管理员开通」误报为可用）；另剥离企微注入的 `extra_identity_context`（明令禁止外泄，原会进入写操作工具输出）；新增日程 AI 工具 + REST 端点。实测真实数据：待办 读/创建/完成 ✓、日程 85 条 ✓、AI 对话（真实日程+待办，含时间冲突提示）✓ | system-config agent |
| 2026-09-18 | d280b27 | ä¼å¾®æºå¨äººééåç«¯ä¸çº¿ï¼ä¸¤å¥è¿æ¥å¨é¡µï¼Bot å­è¯/æ«ç ææ + åç±»ææç©éµï¼+ åç«¯ä¿®å¤ï¼CLI å·¥ä½ç®å½ç§»åºæ²ç®±æç»çéç½®ç®å½ãæ«ç ç­å¾äºç»´ç åå¥å®æ | system-config agent |
| 2026-09-17 | 130436b | **企微官方 CLI（wecom-cli）机器人通道集成**：后端镜像内嵌静态二进制（v1.3.0，双架构 sha512 校验）+ `/data/wecom-cli` 数据卷；连接器「企业微信」卡片新增机器人通道配置（Bot ID 存 extra_config、Bot Secret 走新增加密列 V83），保存后一键授权（`auth init --bot-id/--secret`，非交互）与扫码兜底；品类授权矩阵（8 品类体检 + 企微续期链接逐字透传）；11 个 AI 工具（通讯录/待办读写/会议含纪要转写/文档/消息/邮件）；探活合并双通道。附带修复两处既有 bug：**SSE 异步分派被 401 覆盖**（AI 助手对话报「登录已失效」，放行 ASYNC/ERROR 分派）与**出站 provider 命名不一致**（glm vs zhipu，GLM 会话空流）。部署注意：bind mount 目录需 `chown spring:spring /data/wecom-cli`（uid 999） | system-config agent |

### ⚠️ 部署操作提醒（2026-09-17）

重建 `frontend` / `frontend-pro` 容器后**必须同时 `docker restart superwork-bu-nginx`**：
nginx upstream 用 `server frontend:80` 形式在启动时解析一次，容器重建换 IP 后仍指向旧地址。

当前入口（2026-09-17 起）：
- `:18080` = frontend-pro（Ant Design Pro，默认入口）
- `:18088` = 旧 Vue 前端（兜底）
- `:18081` = backend
- `:18084` 已废弃，不再映射


## 回滚

备份目录：`241:/home/openclaw/deploy-backups/<时间戳>/`（含上一版 jar/dist/DEPLOYED_COMMIT）。
回滚 = 恢复备份文件 + 重建容器 + 更新 DEPLOYED_COMMIT 与本记录。

## ⚠️ 事故记录（2026-09-07）

**502 事故**：aiagent 分支 agent 在 master 部署后，把 **aiagent 分支的 jar**（含 V50/V51/V52 迁移）
rsync 覆盖了 241 的 jar 并重启。随后 master 的镜像重建又用回旧镜像+aiagent jar 混合，
Flyway checksum 反复 mismatch → 后端 crash loop → 502。

**教训（补充铁律）**：
1. rsync/构建前必须 `git -C <worktree> branch --show-current` 确认在 **master** 上；
   非 master worktree 的 `backend/target/*.jar` **不得**同步到 241。
2. 迁移文件（db/migration）属分支独有资产：master 部署时若 flyway 报 checksum mismatch/
   failed migration，先确认这些迁移文件是否属于 master——不属于则删除
   `flyway_schema_history` 中对应版本记录（保留业务表），再重启。
3. 修复过程中 ai_connector / ai_agent 相关表已保留，aiagent 分支下次部署（其 jar 含
   V50/51/52 文件）会重新执行这些迁移——需要先确保 flyway history 与该分支对齐。

**当前状态（2026-09-07 09:15）**：master jar 已恢复（369fafc7），flyway history 清理至
V48+V50，backend UP、UI 200。V50 记录保留（master jar 无此文件但历史存在不影响）。
| 2026-09-07 | a1233f2 | 其他成本新增短信成本(sms)类型：CRUD/汇总/前端全链路 | bigwork agent |
| 2026-09-07 | 10dca06 | 交付利润备注收敛：合计行「销/线/无」文号徽标+悬浮；口径说明ⓘ弹层 | bigwork agent |
| 2026-09-07 | 5fa29d1 | 业务线合计行开放其他成本维护；其他成本单元格悬浮明细 | bigwork agent |
| 2026-09-07 | a047bff | 删除越权测试项目；交付利润页横幅文字收敛为 tooltip（口径说明ⓘ保留） | bigwork agent |
| 2026-09-07 | 4982025 | 交付利润页工具栏与概览卡间距调整；排查2月1.89元补录（已计入，万元显示四舍五入不可见，非bug） | bigwork agent |
| 2026-09-07 | 2459a54 | 交付与利润 tab 移至第二位；H1/H2/YTD 营收核算全面核对（h1+h2==ytd 全部成立，无计算错误） | bigwork agent |
| 2026-09-07 | 2459a54 | H1/H2/YTD 全链路核查：后端窗口计算+前端渲染均正确（h1+h2==ytd）；tab 顺序最终确认 | bigwork agent |
| 2026-09-07 | 2459a54 | H1/H2 逐行 Playwright 核查（按真实数据 mock）：全部行随窗口变化，前端后端无 bug | bigwork agent |
| 2026-09-08 | 81b67b1 | 修复交付与利润 H1/H2/全年切换部分行冻结(嵌套 template v-for 片段锚点错位,改 tr/td 直挂 key) | bigwork agent |
| 2026-09-08 | 8980d3d | 修复其他成本编辑保存报"业务线不能为空"(PUT 请求体漏带归属字段) | bigwork agent |
