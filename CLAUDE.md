# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**项目名称：** 电商BU内部管理系统

**项目描述：** 需求全生命周期管理平台

**核心业务功能：**
- 管理需求全生命周期：评估 → 设计 → 确认 → 开发 → 测试 → 上线 → 交付 → 验收
- 组织架构管理：业务线 → 项目（支持父子项目）→ 团队成员
- 工时管理：预估工时 vs 实际工时对比分析
- 数据统计：人效分析、业务线健康度、需求全景、进度对比
- RBAC权限管理：基于角色的权限控制
- 工作流配置：需求状态流转规则管理

**重要文档：**
- 完整设计文档：`docs/superpowers/specs/2026-04-01-bu-management-system-design.md`
- Phase 2 实施计划：`docs/superpowers/plans/2026-04-02-phase3-backend-api-quick.md`

---

## Technology Stack

### Backend
- **Framework:** Spring Boot 3.2.4
- **ORM:** MyBatis Plus 3.5.x
- **Database:** MySQL 8.0
- **Authentication:** JWT (jjwt 0.12.x)
- **Password Encoding:** BCrypt
- **API Docs:** Knife4j + SpringDoc OpenAPI 3
- **Migration:** Flyway 9.22.x

### Frontend
- **Framework:** Vue 3.4.x (Composition API)
- **Build Tool:** Vite 5.x
- **UI Library:** Element Plus 2.5.x
- **State Management:** Pinia 2.x
- **Router:** Vue Router 4.x
- **Language:** TypeScript 5.x

### Infrastructure
- **Container:** Docker + Docker Compose
- **Services:** MySQL 8.0, Redis 7-alpine, MinIO, Nginx

---

## Project Structure

```
├── backend/                    # Spring Boot 后端项目
│   ├── src/main/java/com/bu/management/
│   │   ├── controller/        # REST API 控制器 (23个)
│   │   ├── service/           # 业务服务层 (24个)
│   │   ├── mapper/            # MyBatis Plus Mapper (21个)
│   │   ├── entity/            # 数据实体类 (19个)
│   │   ├── dto/               # 数据传输对象
│   │   ├── vo/                # 视图对象
│   │   ├── config/            # 配置类 (Security, Web, Knife4j等)
│   │   ├── security/          # 安全相关 (JWT Filter, Permission Interceptor)
│   │   ├── annotation/        # 自定义注解 (@RequirePermission)
│   │   ├── exception/        # 异常处理
│   │   └── util/              # 工具类
│   └── src/main/resources/
│       ├── db/migration/      # Flyway 数据库迁移
│       │   ├── V1__init_schema.sql              # 数据库表结构
│       │   ├── V2__init_workflow_config.sql     # 工作流初始配置
│       │   ├── V3__init_test_data.sql           # 测试数据
│       │   └── V4__init_sys_menu_and_permission.sql  # 系统菜单和权限
│       └── application.yml     # 应用配置
│
├── frontend/                   # Vue 3 前端项目
│   ├── src/
│   │   ├── views/            # 页面组件 (11个)
│   │   │   ├── HomeView.vue           # 首页/工作台
│   │   │   ├── LoginView.vue         # 登录页
│   │   │   ├── RequirementsView.vue   # 需求管理列表
│   │   │   ├── RequirementDetailView.vue  # 需求详情
│   │   │   ├── TasksView.vue         # 任务管理
│   │   │   ├── StatisticsView.vue    # 数据统计
│   │   │   ├── OrganizationView.vue # 组织架构
│   │   │   ├── SystemUserView.vue    # 用户管理
│   │   │   ├── SystemRoleView.vue    # 角色管理
│   │   │   ├── SystemMenuView.vue    # 菜单管理
│   │   │   └── SystemWorkflowView.vue # 工作流配置
│   │   ├── layouts/           # 布局组件
│   │   ├── components/         # 公共组件
│   │   ├── router/            # 路由配置
│   │   ├── stores/            # Pinia 状态管理
│   │   ├── utils/             # 工具函数 (API服务)
│   │   ├── types/             # TypeScript 类型定义
│   │   └── styles/            # 全局样式
│   └── vite.config.ts
│
├── docker/                     # Docker 配置
│   ├── docker-compose.yml      # 容器编排
│   ├── mysql/                  # MySQL 配置
│   ├── redis/                 # Redis 配置
│   ├── minio/                 # MinIO 配置
│   └── nginx/                 # Nginx 配置
│
└── docs/                       # 设计文档
    └── superpowers/
        ├── specs/             # 设计规格文档
        └── plans/             # 实施计划文档
```

---

## Database Schema

### 数据库表 (20张)

**组织架构模块 (5张):**
| 表名 | 说明 |
|------|------|
| `business_line` | 业务线表 |
| `project` | 项目表（支持父子项目层级） |
| `user` | 用户表 |
| `project_member` | 项目成员关系表 |
| `customer_contact` | 客户联系人表 |

**需求管理模块 (6张):**
| 表名 | 说明 |
|------|------|
| `requirement` | 需求主表 |
| `requirement_evaluation` | 需求评估表 |
| `requirement_design` | 需求设计汇总表 |
| `design_work_log` | 设计工作记录表 |
| `requirement_confirmation` | 需求确认表 |
| `requirement_delivery` | 需求交付表 |

**任务工时模块 (2张):**
| 表名 | 说明 |
|------|------|
| `task` | 任务表 |
| `work_log` | 工时记录表 |

**事项管理模块 (1张):**
| 表名 | 说明 |
|------|------|
| `issue` | 事项表（Bug、运维、临时需求等） |

**工作流配置模块 (1张):**
| 表名 | 说明 |
|------|------|
| `workflow_config` | 工作流配置表 |

**系统管理模块 (4张):**
| 表名 | 说明 |
|------|------|
| `sys_menu` | 系统菜单表 |
| `sys_permission` | 系统权限表 |
| `sys_role` | 系统角色表 |
| `sys_user_role` | 用户角色关联表 |
| `sys_role_menu` | 角色菜单关联表 |
| `sys_role_permission` | 角色权限关联表 |

**其他:**
| 表名 | 说明 |
|------|------|
| `attachment` | 附件表 |
| `flyway_schema_history` | Flyway 迁移历史表 |

---

## Implemented Features

### Backend APIs (已实现的接口)

| 模块 | 接口 | 说明 |
|------|------|------|
| **认证** | POST /api/auth/login | 用户登录 |
| | POST /api/auth/register | 用户注册 |
| **业务线** | GET/POST /api/business-lines | 获取/创建业务线 |
| | PUT/DELETE /api/business-lines/{id} | 更新/删除业务线 |
| **项目** | GET/POST /api/projects | 获取/创建项目 |
| | GET/PUT/DELETE /api/projects/{id} | 项目详情/更新/删除 |
| **需求** | GET/POST /api/requirements | 需求列表/创建 |
| | GET/PUT/DELETE /api/requirements/{id} | 需求详情/更新/删除 |
| **任务** | GET/POST /api/tasks | 任务列表/创建 |
| | GET/PUT/DELETE /api/tasks/{id} | 任务详情/更新/删除 |
| | GET /api/tasks/requirement/{requirementId} | 按需求获取任务 |
| **事项** | GET/POST /api/issues | 事项列表/创建 |
| | GET/PUT/DELETE /api/issues/{id} | 事项详情/更新/删除 |
| **统计** | GET /api/statistics/requirements | 需求统计 |
| | GET /api/statistics/tasks | 任务统计 |
| **工作流** | GET /api/workflow-configs | 获取工作流配置 |
| | GET /api/workflow-configs/type/{type} | 按类型获取工作流 |
| | GET /api/workflow-configs/next-statuses | 获取下一状态 |
| **系统管理** | GET /api/system/menus | 获取菜单列表 |
| | GET /api/system/roles | 获取角色列表 |
| | GET /api/system/permissions | 获取权限列表 |
| | GET /api/system/menus/role/{roleId} | 获取角色菜单 |
| | POST /api/system/menus/assign | 分配菜单给角色 |
| **其他** | GET /api/customer-contacts | 客户联系人列表 |
| | GET /api/project-members/{projectId} | 项目成员列表 |

### Frontend Pages (已实现的页面)

| 页面 | 路由 | 说明 |
|------|------|------|
| 登录页 | /login | 用户登录 |
| 工作台/首页 | / | 统计数据面板、最近需求、快捷操作 |
| 需求管理 | /requirements | 需求列表、筛选、创建 |
| 需求详情 | /requirements/:id | 需求详情、任务、子需求 |
| 任务管理 | /tasks | 任务列表 |
| 数据统计 | /statistics | 数据看板 |
| 组织架构 | /organization | 业务线-项目树形结构 |
| 用户管理 | /system/users | 用户CRUD |
| 角色管理 | /system/roles | 角色CRUD、菜单/权限分配 |
| 菜单管理 | /system/menus | 菜单树形展示 |
| 工作流配置 | /system/workflow | 工作流规则管理 |

---

## API Response Format

```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": "2026-04-04T22:00:00.000000"
}
```

**错误响应:**
```json
{
  "code": 400,
  "message": "错误信息",
  "timestamp": "2026-04-04T22:00:00.000000"
}
```

**分页响应 (data字段):**
```json
{
  "records": [...],
  "total": 100,
  "size": 10,
  "current": 1,
  "pages": 10
}
```

---

## Authentication & Authorization

### JWT Token
- Header: `Authorization: Bearer <token>`
- Token 有效期: 24小时
- Refresh Token 有效期: 7天

### RBAC 权限模型

| 角色 | 代码 | 权限说明 |
|------|------|----------|
| BU负责人 | BU_ADMIN | 全局数据访问 |
| 项目经理 | PM | 按项目隔离 |
| 技术经理 | TECH_MANAGER | 按项目隔离 |
| 产品经理 | PRODUCT | 按业务线 |
| UI设计 | UI_DESIGN | 任务分配 |
| 开发 | DEVELOPER | 任务分配 |
| 测试 | TESTER | 任务分配 |

### 数据权限隔离
- `DataPermissionService` 实现项目级数据隔离
- 项目经理和技术经理只能访问其参与的项目数据
- 通过 `project_member` 表关联用户和项目

### 权限注解
使用 `@RequirePermission` 注解进行方法级权限控制:
```java
@RequirePermission({"requirement:list"})
public Result<Page<Requirement>> list(...) { ... }
```

---

## Development Commands

### Backend

```bash
cd backend

# 开发模式运行
mvn spring-boot:run

# 构建
mvn clean package -DskipTests

# 运行测试
mvn test

# 查看SQL日志
# 已配置在 dev profile 中，SQL会打印到控制台
```

### Frontend

```bash
cd frontend

# 安装依赖
npm install

# 开发模式运行 (http://localhost:5173)
npm run dev

# 构建生产版本
npm run build

# 类型检查
npm run type-check
```

### Docker

```bash
cd docker

# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止所有服务
docker-compose down

# 重建并启动
docker-compose up -d --build
```

---

## Test Accounts

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| admin | 123456 | BU_ADMIN | 系统管理员 |
| pm_zhang | 123456 | PM | 项目经理 |
| tech_li | 123456 | TECH_MANAGER | 技术经理 |
| product_wang | 123456 | PRODUCT | 产品经理 |
| dev_zhao | 123456 | DEVELOPER | 前端开发 |
| dev_qian | 123456 | DEVELOPER | 后端开发 |
| test_sun | 123456 | TESTER | 测试工程师 |
| ui_zhou | 123456 | UI_DESIGN | UI设计师 |

---

## Current Issues & Fixes

### 已解决的问题

1. **Task Entity 字段不匹配**
   - 问题: Task 实体包含 `priority`, `startedAt`, `completedAt` 字段，但数据库表中不存在
   - 影响: 统计API调用失败
   - 解决: 移除实体中不存在的字段，更新相关 Service

2. **BCrypt 密码哈希错误**
   - 问题: V3 迁移脚本中的 BCrypt 哈希值不正确
   - 影响: 用户无法登录
   - 解决: 使用正确的 BCrypt 哈希值重新更新用户密码

3. **Flyway Migration Checksum 不匹配**
   - 问题: 迁移脚本被修改后与数据库记录不匹配
   - 影响: 应用启动失败
   - 解决: 设置 `flyway.out-of-order: true` 允许乱序执行

---

## Known Issues / TODO

1. **数据权限过滤待完善**
   - `DataPermissionService` 已创建但 `RequirementController.list()` 刚完成改造
   - 需要在实际使用中验证权限过滤效果

2. **前端工作流配置页面**
   - 已创建 `SystemWorkflowView.vue`
   - 表单提交功能待实现（目前显示"功能待实现"）

3. **前端任务管理页面**
   - 已创建 `TasksView.vue` 基础结构
   - 与后端 Task API 的完整集成待验证

4. **Swagger/Knife4j 文档**
   - 已配置但可能需要调整分组和描述

---

## Configuration Files

### Backend - application-dev.yml
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/bu_management
    username: bu_admin
    password: bu_admin123
  flyway:
    enabled: true
    out-of-order: true  # 允许乱序迁移
```

### Frontend - .env
```
VITE_API_BASE_URL=http://localhost:8081
```

---

## Code Style Guidelines

### Backend (Java)
- 使用 Lombok 减少样板代码
- MyBatis Plus LambdaQueryWrapper 进行类型安全查询
- Service 层处理业务逻辑，Controller 层只做参数校验
- 使用 `Result<T>` 统一响应格式

### Frontend (Vue/TypeScript)
- Vue 3 Composition API (`<script setup lang="ts">`)
- 组件 props 使用 interface 定义
- API 调用统一通过 `src/utils/api.ts` 的 ApiService 类
- 样式使用 CSS 变量（`var(--gray-500)` 等）和 Element Plus 组件

---

## Important Notes

1. **数据库更改**: 必须通过 Flyway 迁移脚本管理，不能直接手动修改数据库
2. **API 更改**: 同时更新 Controller 和（如果需要）前端 API 服务
3. **实体更改**: 确保 Java 实体字段与数据库表结构完全匹配
4. **密码问题**: 如果用户无法登录，检查 BCrypt 哈希是否正确

---

## Service Ports

| Service | Port | URL |
|---------|------|-----|
| Backend | 8081 | http://localhost:8081 |
| Frontend | 5173 | http://localhost:5173 |
| MySQL | 3306 | localhost:3306 |
| Redis | 6379 | localhost:6379 |
| MinIO Console | 9001 | http://localhost:9001 |
| Knife4j Docs | 8081 | http://localhost:8081/doc.html |
