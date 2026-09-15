# 部署协调约定（所有 agent 必须遵守）

> 更新：2026-09-04；维护者：master 分支工作 agent
> 适用范围：`superWork-claude-sp` 主工作空间 + `superWork-sp-agent`/`bigwork` 等 worktree 的所有 agent

## 铁律

1. **先合并，后部署**：任何部署到 241 的代码必须已经合入 `master` 分支。
   禁止直接从 feature/worktree 分支构建部署。241 上的 jar/dist 必须与 origin/master 一致。

2. **master 是唯一部署源**：部署产物（jar/dist）只从 `master` 分支的 `backend/target/management-1.0.0.jar`
   与 `frontend/dist/` 构建。worktree（bigwork/aiagent 等）只负责开发与自测。

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
ssh server-241 'cd docker && docker compose -f docker-compose.241.yml up -d --build backend frontend'
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
