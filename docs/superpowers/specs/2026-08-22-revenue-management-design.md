# 全渠道项目营收管理设计

## 目标

在 BU 管理系统中增加全渠道项目的营收与成本管理能力，替代目前手工维护的 Excel 测算表（`项目营收拆解.xlsx`）。支持按月导入工时成本、合同交付营收，手动维护其他成本和 H2 预估，最终在看板中展示各业务线/项目的毛利与毛利率。

## 数据来源与口径

| 数据 | 来源 | 导入方式 | 口径 |
|---|---|---|---|
| 交付营收 | 合同明细 Excel（应收金额） | 按月导入 | 应收金额，非实收 |
| 交付+销售工时成本 | 工时管理系统导出 Excel | 按月导入 | 工时 × 人月单价 |
| 协力/服务器/其他成本 | 手动录入 | CRUD | — |
| H2 预估交付 | 手动录入 | CRUD | — |

- 所有金额数据库存元（BIGINT），前端展示默认万元。
- 营收取"应收金额"字段。
- 单位转换：元 ÷ 10000 = 万元。

## 业务线拆分

### 定制 / SAAS（按项目拆分）

每项目独立展示：年度应收、已收、月度交付工时、交付成本、销售成本、协力成本、服务器成本、其他成本、合计成本、毛利、毛利率。

### 会员通（业务线整体，不拆项目）

只看业务线级汇总：月度营收 / 成本 / 毛利。合同明细中收款款项类型含"会员通"的直接归入此业务线，不经过品牌映射。

## 数据库设计（Flyway V34）

### `revenue_project_mapping` — 项目映射表

```sql
CREATE TABLE revenue_project_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL COMMENT 'cost_project / contract_brand',
    source_name VARCHAR(200) NOT NULL COMMENT '工时系统项目名或合同品牌名',
    project_id BIGINT NULL COMMENT '关联项目ID；NULL=业务线级',
    business_line_id BIGINT NULL COMMENT '直接归属业务线（会员通等不拆项目的）',
    category VARCHAR(20) NOT NULL DEFAULT 'delivery' COMMENT 'delivery/sales/product',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source (source_type, source_name),
    INDEX idx_mapping_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收项目映射';
```

### `revenue_monthly_cost` — 月度成本

```sql
CREATE TABLE revenue_monthly_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    category VARCHAR(20) NOT NULL DEFAULT 'delivery' COMMENT 'delivery/sales/product',
    work_hours DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '工时（人月）',
    work_cost BIGINT NOT NULL DEFAULT 0 COMMENT '工时成本（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project_cat (year_month, project_id, category),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度成本';
```

### `revenue_monthly_income` — 月度交付营收

```sql
CREATE TABLE revenue_monthly_income (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '收款月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    contract_count INT NOT NULL DEFAULT 0,
    receivable_amount BIGINT NOT NULL DEFAULT 0 COMMENT '应收金额（元）',
    received_amount BIGINT NOT NULL DEFAULT 0 COMMENT '实收金额（元）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_project (year_month, project_id),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度交付';
```

### `revenue_manual_entry` — 手动维护项

```sql
CREATE TABLE revenue_manual_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL COMMENT '月份 YYYY-MM',
    project_id BIGINT NULL COMMENT '关联项目；NULL=业务线级',
    business_line_id BIGINT NOT NULL COMMENT '冗余业务线ID',
    entry_type VARCHAR(30) NOT NULL COMMENT 'h2_estimate/partner_cost/server_cost/other_cost',
    amount BIGINT NOT NULL DEFAULT 0 COMMENT '金额（元）',
    remark VARCHAR(500) DEFAULT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_bl (year_month, business_line_id),
    INDEX idx_month_project (year_month, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收手动维护项';
```

### 权限初始化

```sql
INSERT INTO sys_permission (code, name, description, type, menu_id) VALUES
('revenue:view', '查看营收管理', '查看全渠道营收看板', 'menu', NULL),
('revenue:manage', '管理营收数据', '导入/维护营收成本数据', 'button', NULL);
```

授权给 DIRECTOR 和 BUSINESS_OWNER 角色。

## 项目映射规则

### 工时系统项目名 → 系统项目

首次导入时按关键词自动匹配：

| 工时系统项目名关键词 | 匹配系统项目 | 分类 |
|---|---|---|
| 含"皇家宠物" | project_id = 1 | delivery |
| 含"Speedo" | project_id = 10 | delivery |
| 含"澳优" | project_id = 8 | delivery |
| 含"飞鹤" | project_id = 7 | delivery |
| 含"逢时" | project_id = 18 | product |
| 含"黄天鹅" | project_id = 15 | product |
| 含"会员通【销售】" | business_line_id = 会员通 | sales |
| 含"会员通【项目】" | business_line_id = 会员通 | delivery |
| 含"全域云鹿Saas【销售】" | business_line_id = SAAS | sales |
| 含"全域云鹿定制【销售】" | business_line_id = 定制 | sales |
| 含"全域云鹿定制【项目】" | 无具体项目→待处理 | delivery |
| 含"全域私域精准" | business_line_id = 全域精准 | delivery |
| 含"其他事项" | 待处理 | — |
| 含"京博" | 待处理 | sales |

未匹配的记录标记 `project_id = NULL AND business_line_id IS NULL`，在前端映射维护页面高亮提示。

### 合同品牌名 → 系统项目

| 品牌名 | 系统项目 |
|---|---|
| 皇家宠物 | project_id = 1 |
| speedo | project_id = 10 |
| 澳优 | project_id = 8 |
| 飞鹤 | project_id = 7 |
| 逢时 | project_id = 18 |
| 黄天鹅 | project_id = 15 |
| 海普诺凯 | project_id = 9 |
| 其他品牌 | 归"其他"，business_line_id = 定制 |

合同中 `收款款项类型` 含"会员通"的行**跳过品牌匹配**，直接归入会员通业务线。

### 分类判断

工时系统项目名后缀：

- `【交付】` 或 `【产研】` → category = delivery
- `【销售】` → category = sales
- 无后缀 → 默认 delivery

## 后端 API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/revenue/summary?year=2026` | revenue:view | 年度汇总：按业务线→项目→月份展开 |
| POST | `/api/revenue/import/cost` | revenue:manage | 上传工时成本 Excel |
| POST | `/api/revenue/import/income` | revenue:manage | 上传合同营收 Excel |
| GET | `/api/revenue/mappings?sourceType=` | revenue:view | 映射列表 |
| PUT | `/api/revenue/mappings/{id}` | revenue:manage | 更新映射 |
| GET | `/api/revenue/manual?year=&month=` | revenue:view | 手动项列表 |
| POST | `/api/revenue/manual` | revenue:manage | 新增手动项 |
| PUT | `/api/revenue/manual/{id}` | revenue:manage | 更新手动项 |
| DELETE | `/api/revenue/manual/{id}` | revenue:manage | 删除手动项 |

### Excel 导入逻辑

#### 成本导入

1. 解析上传的 xlsx 文件，读取 Sheet "成本分析-项目视角"。
2. 逐行提取：月份、项目名、工时、工时成本。
3. 按 `source_name + source_type=cost_project` 查映射表。
4. 有映射 → 写入/更新 `revenue_monthly_cost`。
5. 无映射 → 自动创建映射记录（尝试关键词匹配），标记待人工确认。
6. 同一月份+项目+分类已存在则覆盖（upsert 语义）。
7. 返回导入统计：成功 N 条、新增映射 M 条、待确认 K 条。

#### 营收导入

1. 解析上传的 xlsx 文件，读取 Sheet "合同明细"。
2. 逐行提取：收款销售月份、品牌、收款款项类型、应收金额、实收金额。
3. 如果款项类型含"会员通" → 直接归入会员通业务线。
4. 否则按 `source_name = 品牌 + source_type=contract_brand` 查映射。
5. 按月份 + 项目聚合，写入/覆盖 `revenue_monthly_income`。
6. 返回导入统计。

### Summary API 返回结构

```json
{
  "code": 200,
  "data": {
    "year": 2026,
    "totalReceivable": 256340000,
    "totalCost": 193270000,
    "totalProfit": 63070000,
    "profitRate": 0.2461,
    "monthlyTrend": [
      { "month": "2026-01", "income": 30000000, "cost": 25000000 }
    ],
    "businessLines": [
      {
        "businessLineId": 1,
        "businessLineName": "全渠道云鹿定制",
        "type": "project_breakdown",
        "totalReceivable": 135000000,
        "totalCost": 84580000,
        "totalProfit": 50420000,
        "projects": [
          {
            "projectId": 1,
            "projectName": "皇家项目",
            "receivable": 135000000,
            "deliveryHours": 71.2976,
            "deliveryCost": 64286373,
            "salesCost": 44034149,
            "partnerCost": 20000000,
            "serverCost": 0,
            "otherCost": 5000000,
            "totalCost": 133320522,
            "profit": 1679478,
            "profitRate": 0.0124,
            "h2Estimate": null,
            "months": [
              { "month": "2026-01", "income": 8000000, "hours": 4.77, "cost": 10774476 }
            ]
          }
        ]
      },
      {
        "businessLineId": 3,
        "businessLineName": "会员通",
        "type": "business_line_summary",
        "totalReceivable": 60000000,
        "totalCost": 45000000,
        "totalProfit": 15000000,
        "profitRate": 0.25,
        "months": [
          { "month": "2026-01", "income": 8000000, "cost": 6000000 }
        ]
      }
    ]
  }
}
```

## 前端页面

路由 `/revenue`，菜单"营收管理"。

### 页面布局

```text
┌────────────────────────────────────────────────────────────┐
│ [年份选择 ▼]                    [导入成本] [导入营收] [维护] │
├────────────────────────────────────────────────────────────┤
│  年度累计营收      累计成本       毛利         毛利率       │
│  ¥2,563万         ¥1,933万      ¥631万       24.6%       │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  月度趋势图（营收 vs 成本 折线）                               │
│                                                            │
├────────────────────────────────────────────────────────────┤
│  业务线汇总表                                               │
│  ┌─────────┬───────┬──────┬──────┬──────┬──────┬──────┐   │
│  │ 项目     │ 应收   │ 工时  │ 交付  │ 销售  │ 合计  │ 毛利率│   │
│  ├─────────┼───────┼──────┼──────┼──────┼──────┼──────┤   │
│  │定制-皇家 │ 135.0 │71.30 │64.29 │44.03 │121.4 │ 1.2% │   │
│  │定制-Speedo│ 33.7 │ 5.01 │ 3.78 │ 9.08 │14.35 │27.5% │   │
│  │...      │       │      │      │      │      │      │   │
│  ├─────────┼───────┼──────┼──────┼──────┼──────┼──────┤   │
│  │SAAS-逢时 │  8.79 │14.94 │25.86 │ 3.71 │39.47 │-77.9%│   │
│  │...      │       │      │      │      │      │      │   │
│  ├─────────┼───────┼──────┼──────┼──────┼──────┼──────┤   │
│  │会员通    │ 60.0  │ —   │  —   │  —   │ 45.0 │ 25.0%│   │
│  └─────────┴───────┴──────┴──────┴──────┴──────┴──────┘   │
│                                                            │
├────────────────────────────────────────────────────────────┤
│  Tab: 月度明细 │ 项目映射 │ 手动维护 │ 导入历史                │
│                                                            │
│  [月度明细] 选择月份后显示当月各项明细                        │
│  [项目映射] 列表 + 编辑弹窗，未匹配的高亮                     │
│  [手动维护] 协力/服务器/其他/H2预估 的CRUD表格               │
│  [导入历史] 记录每次导入的时间、文件名、成功/失败数             │
└────────────────────────────────────────────────────────────┘
```

### 交互细节

- 年份切换重新加载 summary。
- 表格金额列默认万元，保留两位小数。
- 毛利率正数绿色，负数红色。
- 导入按钮触发文件选择 → 上传 → 显示结果 toast → 自动刷新看板。
- 项目映射页面未匹配项用 warning 标签标注，点击可编辑关联到正确项目。
- 手动维护支持按月份筛选，新增时自动带入当前选中月份。
- H2 预估交付在项目中以独立列展示，不计入实际成本。

## 权限

- `revenue:view`：管理序列角色可见菜单和看板。
- `revenue:manage`：仅 DIRECTOR/BUSINESS_OWNER 可操作导入和维护。

前端路由守卫使用 roleAccess `management`。

## 实现范围

### 后端新增

- Flyway V34
- Entity: RevenueProjectMapping, RevenueMonthlyCost, RevenueMonthlyIncome, RevenueManualEntry
- Mapper: 对应四个 Mapper
- Service: RevenueService（汇总计算+Excel 解析）, RevenueMappingService
- Controller: RevenueController
- DTO: RevenueSummaryVO, RevenueImportResultVO, RevenueManualEntryDTO
- Config: Excel 解析使用 Apache POI（需引入依赖）

### 前端新增

- `frontend/src/views/RevenueView.vue`
- `frontend/src/types/revenue.ts`
- API 方法追加到 `api.ts`
- 路由和菜单配置
- ECharts 或 Element Plus 内置图表用于月度趋势

### 不修改

- 现有需求/任务/大事儿等模块
- 现有权限体系结构
- 工时管理系统的导出格式

## 验收标准

- [ ] 成本 Excel 导入后，月度成本数据与源文件一致。
- [ ] 营收 Excel 导入后，月度营收数据与源文件一致。
- [ ] 未匹配的项目映射可在前端修正后重新归集。
- [ ] 手动录入协力/服务器/H2预估后看板即时反映。
- [ ] 会员通只显示业务线汇总，无项目行。
- [ ] 定制/SAAS 按项目拆分展示。
- [ ] 毛利 = 营收 - (交付成本 + 销售成本 + 协力 + 服务器 + 其他)。
- [ ] 毛利率 = 毛利 / 营收。
- [ ] 后端测试通过。
- [ ] 前端构建通过。
- [ ] 生产部署后可用真实账号访问。
