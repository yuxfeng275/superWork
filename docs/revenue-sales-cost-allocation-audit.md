# 销售工时/成本 → 成单项目 拆解审计（调研沉淀）

> 目的：作为「项目交付营收（利润）」后续实现与验收依据。本文档只沉淀结论，
> 不改代码、不改生产数据。数据截至 2026-09-02（生产 241）。

---

## 1. 当前数据链路与缺口

### 1.1 可用字段（真实存在）

| 表 | 字段 | 含义 / 可用于拆解的线索 |
|---|---|---|
| `revenue_worklog_entry`（销售行） | `work_type='sales'`、`sales_kind`（`specific`/`pool`/`other`）、`sales_project_id`、`project_name_raw`、`work_note`、`tags`（商机集合按工作说明自动打的品牌/项目标签） | 工时行才带工作说明与标签 |
| `revenue_cost_entry`（销售行） | `work_type='sales'`、`sales_kind`、`sales_project_id`、`project_name_raw`、`hours`、`cost_amount`（元，成本口径依据） | **cost 行没有 work_note/tags**，是月度聚合行 |
| `revenue_sales_project` | `business_line_id`、`name`、`opportunity_id`（手动绑定，可空） | 具体销售项目（如 京博）→ 商机 的唯一桥 |
| `sales_opportunity` | `customer`、`business_line`(名称字符串)、`status`（含 已流失） | 客户名是唯一可与合同对齐的业务字段 |
| `revenue_contract_entry` | `detail_no`(唯一)、`brand`、`customer`、`biz_line_raw`(收款款项类型)、`biz_line_id`、`project_id`(可空)、`receivable_amount`、`sale_month`、`delivery_date` | 成单合同 → 项目归属的登记处 |

### 1.2 明确不存在的关联（缺口）

- **没有** 销售行(worklog/cost) → 合同/成单 的外键（无 contract_no、无 detail_no 关联）。
- **没有** 销售行 → 交付项目 的直接字段（specific 只到 `revenue_sales_project`，后者只到商机）。
- **没有** 商机 → 合同 或 商机 → 项目 的外键；`sales_opportunity.business_line` 仅存名称字符串。
- **没有** 合同 → 商机 的字段。
- cost 销售行是月度聚合，与 worklog 行不 1:1，无法用 work_note/tags 逐行配对。
- 结论：目前**唯一**可确证的成单证据链是
  `销售成本行(specific) → revenue_sales_project → opportunity.customer → 同线同年已映射合同.customer（客户名相等）→ project`。

---

## 2. 已实现的确定性拆分规则与利润口径

### 2.1 拆到项目（仅 full/aggregate 模式业务线；simple 线销售并入单行不拆）

- 可分配条件（同时满足，缺一不可）：
  1. `sales_kind='specific'` 且 `sales_project_id` 命中已绑定商机的 `revenue_sales_project`；
  2. 商机客户名（trim、忽略大小写）命中**同业务线**、**同年收款销售口径**、**已映射项目** 的合同；
  3. 命中项目**唯一**。
- 命中 → 该销售成本行的 `hours/cost_amount` 按行月份落入该项目行（`allocatedSalesHours/Cost`）。
- 不满足 → 保留业务线池（未分配），原因码：

| 原因码 | 含义 |
|---|---|
| `NO_OPP_LINK` | 具体销售项目未关联商机（无客户线索） |
| `NO_MATCH_CONTRACT` | 已关联商机但年内无同客户成单合同 |
| `MULTI_PROJECT` | 同客户成单合同对应多个项目，无法唯一归属 |
| `POOL_NO_EVIDENCE` | 商机集合/其他销售行无成单项目证据（不做均摊/标签推断） |

- 商机集合（pool）、其他（other）、simple 线销售一律不进项目行（避免把售前/未成单费用虚增项目利润）。

### 2.2 利润公式（金额单位：元；`±` = 含预估开关影响）

- 项目行：
  - 毛利 `grossProfit` = 已交付收入(±预估) − 项目工时成本(±预估) − 项目其他成本（销售成本不进项目行，旧口径）
  - **项目真实利润 `trueProfit` = grossProfit − 已分配销售成本(allocatedSalesCost)**（未分配销售成本不从项目重复扣）
- 业务线 totals / 整表 overview：
  - **真实利润 `trueProfit` = Σ项目真实利润 − 未分配销售成本(unallocatedSalesCost)**
  - 数值上等于毛利（销售成本已在业务线层全额计入），但 VO 中 `allocatedSalesCost / unallocatedSalesCost / trueProfit` 均独立成字段。
- 未分配原因按行汇总到 `lines[].salesUnallocatedDetail` 与 `overview.salesUnallocatedDetail`（reason/label/cost，全年）。

---

## 3. 生产样例（2026，已完结月 1–7；不涉及凭据）

- 当前全部销售成本 **838,094.45 元** 处于未分配，已分配 = 0：
  - `POOL_NO_EVIDENCE`（商机集合/其他）：**835,025.45 元**；
  - `NO_MATCH_CONTRACT`：**3,069.00 元** —— 京博【销售】specific 行(2026-07, cost 3069) 绑定商机「京博-CDP…」且状态=**已流失**，无同客户成单合同 → 未分配（符合规则：不把未成单销售费用塞进项目）。
- 2026 汇总（当前实现口径）：OA 合同总额 4,733,455.91；已交付 2,968,047.94；毛利 419,214.32（14.12%）。
- 合同映射分布：定制线 皇家项目 2,191,084.51 / 飞鹤 424,587.28 / Speedo 754,321.00 / 澳优 425,606.98；SAAS 黄天鹅 661,069.80 / 逢时 200,000.00；会员通(项目集) 1,091,275.68；全域精准 5.00。

---

## 4. 仍需产品确认的最小问题（验收前必答）

1. **品牌优先 vs 收款款项类型优先（金额影响最大）**
   当前品牌优先：`收款款项类型=会员通` 的合同被归到皇家/Speedo/飞鹤/澳优/逢时/黄天鹅，共 **674,642.98 元（2026 已交付 443,152.00）**，会员通(项目集)因此少算约 67.5 万（现 1,091,275.68 → 若类型优先 1,765,918.66）。
   → 确认：会员通代运营/会员通 2.0/会员通+增值服务 类合同归「会员通业务线(项目集)」还是按品牌归交付项目？
2. **定制线福田待映射**：北汽福田 2 条（共 4,650 元，2026 已交付）应挂哪个项目？现无福田项目——新建还是归既有定制项目？（当前 4 条 pending：另 2 条为 2025 测试合同，建议忽略）
3. **商机集合(pool)是否允许按标签/客户分配**：pool 行带工作说明自动标签且无成单证据时，保持业务线池（当前行为，推荐），还是要求“唯一项目标签 + 同线成单合同”也分摊？specific 行是否接受“仅客户名相等”作为匹配（现无外键）？
4. **跨销售年份、当年交付的合同如何纳入**：2026 文件中 34 条（1,014,494.34 元）sale_month=2024/2025 但交付日期全在 2026YTD。按收款销售年份筛选它们不进 OA/已交付；按交付年份则 2026 已交付 ≈ 3,982,542.28（+34%）。
   → 确认 OA 总额与已交付分别以“收款销售年”还是“交付年”归口，并处理 `sale_month IS NULL`。

---

## 附：文档依据

- 接口契约与字段定义见 `RevenueDeliverySummaryVO`（VO 注释含完整口径）；实现见 `RevenueDeliverySummaryService.allocateSales(...)`。
- 生产数字来源：241 `superwork-bu-mysql`（bu_management）只读查询 + `/api/revenue/delivery/summary?year=2026`（2026-09-02）。
