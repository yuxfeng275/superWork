# 全渠道项目营收管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加全渠道项目营收管理模块，支持按月导入工时成本和合同营收，手动维护协力/服务器/其他成本和 H2 预估，在看板中按业务线/项目展示毛利与毛利率。

**Architecture:** Flyway V34 创建四张表（映射、月度成本、月度营收、手动维护项），后端新增 RevenueService 处理 Excel 解析（Apache POI）和汇总计算，前端新增 RevenueView 页面展示看板和数据管理。会员通业务线整体汇总不拆项目，定制/SAAS 按项目拆分。品牌→项目和工时系统项目名→项目通过 `revenue_project_mapping` 表维护。

**Tech Stack:** Spring Boot 3.2、MyBatis Plus、Flyway、Apache POI、Vue 3、Element Plus、ECharts

---

## File Map

### Backend

- Create: `backend/src/main/resources/db/migration/V34__add_revenue_management.sql`
- Create: `backend/src/main/java/com/bu/management/entity/RevenueProjectMapping.java`
- Create: `backend/src/main/java/com/bu/management/entity/RevenueMonthlyCost.java`
- Create: `backend/src/main/java/com/bu/management/entity/RevenueMonthlyIncome.java`
- Create: `backend/src/main/java/com/bu/management/entity/RevenueManualEntry.java`
- Create: `backend/src/main/java/com/bu/management/mapper/RevenueProjectMappingMapper.java`
- Create: `backend/src/main/java/com/bu/management/mapper/RevenueMonthlyCostMapper.java`
- Create: `backend/src/main/java/com/bu/management/mapper/RevenueMonthlyIncomeMapper.java`
- Create: `backend/src/main/java/com/bu/management/mapper/RevenueManualEntryMapper.java`
- Create: `backend/src/main/java/com/bu/management/dto/RevenueSummaryVO.java`
- Create: `backend/src/main/java/com/bu/management/dto/RevenueImportResultVO.java`
- Create: `backend/src/main/java/com/bu/management/dto/RevenueManualEntryDTO.java`
- Create: `backend/src/main/java/com/bu/management/service/RevenueService.java`
- Create: `backend/src/main/java/com/bu/management/controller/RevenueController.java`
- Modify: `backend/pom.xml` — add Apache POI dependency
- Test: `backend/src/test/java/com/bu/management/service/RevenueServiceTest.java`

### Frontend

- Create: `frontend/src/views/RevenueView.vue`
- Create: `frontend/src/types/revenue.ts`
- Modify: `frontend/src/utils/api.ts` — add revenue API methods
- Modify: `frontend/src/router/index.ts` — add `/revenue` route
- Modify: `frontend/src/layouts/MainLayout.vue` — add menu item

### Contract

- Create: `.trellis/spec/backend/revenue-management-contract.md`

---

## Task 1: Database Migration + Entities + Mappers

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__add_revenue_management.sql`
- Create: entity × 4, mapper × 4
- Modify: `backend/pom.xml`

- [ ] **Step 1: Write V34 migration**

```sql
CREATE TABLE revenue_project_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type VARCHAR(20) NOT NULL COMMENT 'cost_project / contract_brand',
    source_name VARCHAR(200) NOT NULL,
    project_id BIGINT NULL,
    business_line_id BIGINT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'delivery',
    status TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_source (source_type, source_name),
    INDEX idx_mapping_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收项目映射';

CREATE TABLE revenue_monthly_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL,
    project_id BIGINT NULL,
    business_line_id BIGINT NOT NULL,
    category VARCHAR(20) NOT NULL DEFAULT 'delivery',
    work_hours DECIMAL(10,4) NOT NULL DEFAULT 0,
    work_cost BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_pc (year_month, project_id, category),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度成本';

CREATE TABLE revenue_monthly_income (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL,
    project_id BIGINT NULL,
    business_line_id BIGINT NOT NULL,
    contract_count INT NOT NULL DEFAULT 0,
    receivable_amount BIGINT NOT NULL DEFAULT 0,
    received_amount BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_month_p (year_month, project_id),
    INDEX idx_month_bl (year_month, business_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收月度交付';

CREATE TABLE revenue_manual_entry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    year_month VARCHAR(7) NOT NULL,
    project_id BIGINT NULL,
    business_line_id BIGINT NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    amount BIGINT NOT NULL DEFAULT 0,
    remark VARCHAR(500) DEFAULT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_month_bl (year_month, business_line_id),
    INDEX idx_month_p (year_month, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营收手动维护项';
```

- [ ] **Step 2: Add POI dependency to pom.xml**

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
```

- [ ] **Step 3: Create entities and mappers**

Standard MyBatis Plus pattern matching existing entities.

- [ ] **Step 4: Run compile check**

```bash
cd backend && mvn compile
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(revenue): add database schema and entities"
```

---

## Task 2: Revenue Service — Import + Summary

**Files:**
- Create: DTO × 3, Service, Controller
- Test: RevenueServiceTest

- [ ] **Step 1: Write failing tests for import logic**

Test cost import parsing with a mock workbook, mapping resolution, and upsert semantics.
Test income import parsing, brand→project mapping, and 会员通 direct assignment.
Test summary aggregation across business lines and projects.

- [ ] **Step 2: Implement RevenueService**

Key methods:

```java
public RevenueImportResultVO importCostExcel(MultipartFile file)
public RevenueImportResultVO importIncomeExcel(MultipartFile file)
public RevenueSummaryVO getSummary(int year)
private void autoCreateMappings(String sourceName, String sourceType)
```

Import uses `WorkbookFactory.create(file.getInputStream())`.
Mapping lookup by `(source_type, source_name)` unique key.
Upsert on `(year_month, project_id, category)` unique key for cost.
Upsert on `(year_month, project_id)` unique key for income.

- [ ] **Step 3: Implement RevenueController with @RequirePermission**

All endpoints require `revenue:view` or `revenue:manage`.

- [ ] **Step 4: Run targeted tests and full suite**

```bash
mvn -Dtest=RevenueServiceTest test
mvn test
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(revenue): add import and summary service"
```

---

## Task 3: Frontend Page + API + Route

**Files:**
- Create: RevenueView.vue, types/revenue.ts
- Modify: api.ts, router, MainLayout

- [ ] **Step 1: Add TypeScript types and API methods**

Types: RevenueSummary, BusinessLineSummary, ProjectSummary, MonthlyData, RevenueMapping, ManualEntry.
API: getRevenueSummary, importCostExcel, importIncomeExcel, getMappings, updateMapping, getManualEntries, createManualEntry, updateManualEntry, deleteManualEntry.

- [ ] **Step 2: Add route and menu**

Route `/revenue` under main layout, menu label "营收管理" in 数据分析 section, roleAccess management.

- [ ] **Step 3: Implement RevenueView.vue**

Layout per design spec: metric cards → trend chart → summary table → tabs (月度明细/映射/手动维护).
Use ECharts for trend line chart.

- [ ] **Step 4: Run build and verify**

```bash
cd frontend && npm run build
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(revenue): add revenue management page"
```

---

## Task 4: Contract + Full Regression + Deploy

**Files:**
- Create: `.trellis/spec/backend/revenue-management-contract.md`

- [ ] **Step 1: Run full regression**

Backend `mvn test`, frontend full Playwright, build.

- [ ] **Step 2: Deploy to production**

Backup, rsync jar+dist, rebuild backend+frontend containers, health check, smoke test.

- [ ] **Step 3: Verify migration on production MySQL**

Check V34 applied, tables exist, permissions granted.

- [ ] **Step 4: Update DEPLOYED_COMMIT marker**

- [ ] **Step 5: Commit contract**

```bash
git commit -m "docs(revenue): record revenue management contract"
```
