
## 2026-09-10 一期实现完成

- V56 迁移：工时系统集成配置/同步日志/业务线映射 + KPI 目标/周快照/偏差备注/预警规则 + revenue_contract_entry 扩列（行级直接成本/实收/收款状态/mapping_locked）
- 工时系统连接器：WorktimeApiClient（JWT 缓存+401重登）、配置加密存储、连接测试
- 自动同步：合同明细每日（drilldown 分页 upsert，人工映射锁保护）、工时明细+成本分析按月度确认触发（复用现有导入管道，整月覆盖）
- KPI：周快照服务（交付口径，当月人工估算=work_log÷174×综合单价）、预警扫描（红/黄）、备注 CRUD、周日 18:00 定时快照 + 企业微信待办提醒
- 前端：/kpi-report 页面（周报矩阵/目标与规则/数据同步三个 tab）、Excel 导出、文字版摘要
- 契约：.trellis/spec/backend/kpi-weekly-report-contract.md
- 验证：mvn test 328 全过；前端 build 过；本地无 docker 未做真实库迁移验证，待部署后对账

## 2026-09-10 已部署 241 并实测通过

- 部署：rsync jar+dist → 241 docker compose 重建（备份 deploy-backups/kpi-weekly-report-20260910-004223）
- 排障：① 241 DB 有 V53__add_ai_notice_read（aiagent 分支部署过），bigwork 分支缺该文件 → 已补入；② revenue_financial_report 表此前手工建过且有 24 行种子数据 → V55 改 INSERT IGNORE；③ 工时系统分页上限 100 → PAGE_SIZE 调整；④ prod work_log 表结构漂移（有 requirement_id 无 work_content）→ 当月人工估算改自定义 SQL 只取两端共存列
- 实测：工时系统配置（00504）→ 连接测试通过（可见 4 条业务线）；合同同步 173 行（待映射 7）；工时明细 84 行 + 成本分析 14 行（2026-08 已确认月自动同步）；周快照回填 7/5–9/6 共 10 周 × 3 组
- 对账：合计 YTD 交付营收 399.27万，与工时系统销售报表 year_delivery_total 完全一致；会员通 YTD 毛利 98.2万 ≈ Excel 财报口径 99万
- 导出 Excel / 文字摘要 / 前端页面 /kpi-report 均验证 200
- 遗留：待用户填写 2 条偏差备注（云鹿/精准毛利为负）；代码未提交（等用户验收后提交）

## 2026-09-10 部署后热修：交付与利润 NPE

- 现象：/revenue/delivery/summary 报 Window.getOtherCosts() NPE
- 根因：V55 财报数据首次落库触发「财务调节行」，该行 h1/h2/ytd 窗口为默认构造（otherCosts=null），addWindow 合并时 NPE
- 修复：调节行改用 newRevenueWindow() 完整初始化；addWindow/mergeOther 加 null 防御
- 验证：mvn 328 tests pass；241 重新部署后接口 200

## 2026-09-10 侧边栏菜单全量动态化（V57）

- 起因：KPI周报只在 sys_menu 注册但侧边栏硬编码，菜单不显示；用户要求全部动态化以支持角色权限配置
- V57：新增分区组（工作台/销售管理/数据分析），既有页面重新挂载分组并对齐图标/命名/排序；补注册 /ai-assistant、/revenue、/ai-connectors；按原前端硬编码可见性等价种子授权（全员通用/project集合/customer集合/管理角色全量）
- 后端：新增 GET /api/auth/my-menu-tree（SysRoleService.getMenuTreeByUserId + MenuTreeNode VO）；移除 getMenuPathsByUserId 的管理员旁路（配置为唯一权威）；SysRoleMenuPathsTest 更新
- 前端：MainLayout 动态渲染（菜单树 → 分区），无授权记录回退内置默认菜单防锁死；/key-matters 保留领域准入叠加；图标名无效回退 Menu
- 验证：329 tests pass；V57 在 241 应用成功；admin(DIRECTOR) 树完整；SOLUTION_MANAGER 仅见 工作台/销售管理/基础分类/BU驾驶舱，与预期一致
- 契约更新：access-control-contract.md 新增 Sidebar Menu Rendering 节

## 2026-09-10 管理员权限收敛

- 管理员四个角色（DIRECTOR/DEPUTY_DIRECTOR/BUSINESS_OWNER/EFFECTIVENESS_OWNER）：25/25 菜单 + 52/52 权限，确认全量
- BU_ADMIN 缺 11 个后加权限（kpi/revenue/key-matter/seeyon-oa/yunxiao）→ 已补全
- /system/permissions 菜单无前端页面对应（原硬编码也未包含），动态化后成死链 → DB 已隐藏
- 首页 /home → / 映射在前端动态代码处理，router-link 正确跳转
