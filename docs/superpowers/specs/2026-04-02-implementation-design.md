# 电商BU内部管理系统 - 实施设计文档

**创建日期：** 2026-04-02  
**设计方案：** 前后端分离并行开发  
**基于：** docs/superpowers/specs/2026-04-01-bu-management-system-design.md

---

## 全局约定：Beads + Superpowers 工作流

本仓库采用 **Beads + Superpowers** 工作流进行任务管理和实施。

**规则：**
1. **长期任务状态、依赖、阻塞、发现统一维护在 Beads**
2. **不要默认生成 docs/plans/*.md 或其他长期计划文档**
3. **Superpowers 只用于：澄清、规划、验证、review、finish**
4. **任何实现前，先从 Beads 找当前未阻塞的最小任务**
5. **实现后必须：**
   - 运行验证
   - 总结结果
   - 更新 Beads 状态
   - 记录关键决策与下一步

---

## 第一部分：整体架构设计

### 技术栈

**后端：**
- Spring Boot 3.2.x
- MyBatis Plus 3.5.x
- Spring Security 6.x + JWT
- MySQL 8.0
- Redis 7.x
- MinIO（最新稳定版）
- Flyway（数据库版本管理）
- Knife4j（API 文档）

**前端：**
- Vue 3.4.x
- Element Plus 2.x
- Vite 5.x
- Pinia（状态管理）
- Vue Router 4.x
- Axios（HTTP 客户端）
- TypeScript

**基础设施：**
- Docker + Docker Compose
- Nginx（反向代理）
- 内网部署

### 项目结构

```
superWork-claude-sp/
├── docker/                      # Docker 配置
│   ├── docker-compose.yml      # 服务编排
│   ├── mysql/                  # MySQL 配置和初始化脚本
│   ├── redis/                  # Redis 配置
│   ├── minio/                  # MinIO 配置
│   └── nginx/                  # Nginx 配置
├── backend/                     # Spring Boot 后端
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/bu/management/
│   │   │   │   ├── config/           # 配置类
│   │   │   │   ├── controller/       # 控制器
│   │   │   │   ├── service/          # 业务逻辑
│   │   │   │   ├── mapper/           # MyBatis Mapper
│   │   │   │   ├── entity/           # 实体类
│   │   │   │   ├── dto/              # 数据传输对象
│   │   │   │   ├── vo/               # 视图对象
│   │   │   │   ├── enums/            # 枚举
│   │   │   │   ├── exception/        # 异常处理
│   │   │   │   ├── security/         # 安全配置
│   │   │   │   ├── workflow/         # 工作流引擎
│   │   │   │   └── util/             # 工具类
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       ├── application-prod.yml
│   │   │       └── db/migration/     # Flyway 迁移脚本
│   │   └── test/                     # 测试代码
│   ├── pom.xml
│   └── Dockerfile
├── frontend/                    # Vue 3 前端
│   ├── src/
│   │   ├── api/                # API 接口
│   │   ├── assets/             # 静态资源
│   │   ├── components/         # 公共组件
│   │   ├── views/              # 页面组件
│   │   ├── router/             # 路由配置
│   │   ├── stores/             # Pinia 状态管理
│   │   ├── utils/              # 工具函数
│   │   ├── types/              # TypeScript 类型定义
│   │   ├── App.vue
│   │   └── main.ts
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── Dockerfile
└── docs/                        # 文档
    ├── api/                    # API 文档
    ├── database/               # 数据库设计文档
    └── superpowers/            # 设计和实施计划
```

### 部署架构

```
[用户浏览器]
     ↓
[Nginx :80]
     ↓
     ├─→ [Vue Frontend :8080] (Docker)
     └─→ [Spring Boot Backend :8081] (Docker)
          ↓
          ├─→ [MySQL :3306] (Docker)
          ├─→ [Redis :6379] (Docker)
          └─→ [MinIO :9000] (Docker)
```

---

## 第二部分：数据库设计

### 完整数据库 Schema（16 张表）

#### 1. 组织架构模块（5 张表）
- `business_line` - 业务线表
- `project` - 项目表（支持父子项目）
- `user` - 用户表
- `project_member` - 项目成员表
- `customer_contact` - 客户联系人表

#### 2. 需求管理模块（6 张表）
- `requirement` - 需求表
- `requirement_evaluation` - 需求评估表
- `requirement_design` - 需求设计汇总表
- `design_work_log` - 设计工作记录表
- `requirement_confirmation` - 需求确认表
- `requirement_delivery` - 需求交付表

#### 3. 任务工时模块（2 张表）
- `task` - 任务表
- `work_log` - 工时记录表

#### 4. 事项管理模块（1 张表）
- `issue` - 事项表

#### 5. 工作流配置模块（1 张表）
- `workflow_config` - 工作流配置表

#### 6. 附件模块（1 张表）
- `attachment` - 附件表

### Flyway 迁移策略
- `V1__init_schema.sql` - 初始化所有表结构
- `V2__init_workflow_config.sql` - 初始化工作流配置数据
- `V3__init_test_data.sql` - 初始化测试数据（仅开发环境）

详细的 SQL DDL 见：`backend/src/main/resources/db/migration/`

---

## 第三部分：后端架构设计

### 分层架构（Controller → Service → Mapper）

**Controller 层：**
- 接收 HTTP 请求，参数验证
- 调用 Service 层处理业务逻辑
- 返回统一格式的响应

**Service 层：**
- 业务逻辑处理
- 事务管理
- 调用 Mapper 层访问数据库
- 调用外部服务（Redis、MinIO）

**Mapper 层：**
- MyBatis Plus 数据访问
- SQL 映射
- 数据库操作

### 统一响应格式

```java
{
    "code": 200,
    "message": "success",
    "data": {...},
    "timestamp": 1234567890
}
```

### 异常处理

全局异常处理器捕获所有异常，返回统一格式：
- 业务异常：400-499
- 系统异常：500-599
- 认证异常：401
- 权限异常：403

### 核心模块

1. **认证授权模块** - JWT + Spring Security
2. **组织架构模块** - 业务线、项目、用户管理
3. **需求管理模块** - 需求全生命周期
4. **任务工时模块** - 任务分配、工时记录
5. **事项管理模块** - Bug、运维、临时需求
6. **工作流引擎模块** - 状态流转、权限验证
7. **统计分析模块** - 数据看板、人效分析
8. **文件管理模块** - MinIO 文件上传下载

---

## 第四部分：前端架构设计

### 目录结构

```
frontend/src/
├── api/              # API 接口封装
│   ├── auth.ts
│   ├── requirement.ts
│   ├── task.ts
│   └── ...
├── components/       # 公共组件
│   ├── Layout/
│   ├── Table/
│   ├── Form/
│   └── ...
├── views/            # 页面组件
│   ├── Login/
│   ├── Dashboard/
│   ├── Requirement/
│   ├── Task/
│   └── ...
├── router/           # 路由配置
│   ├── index.ts
│   └── modules/
├── stores/           # Pinia 状态管理
│   ├── user.ts
│   ├── menu.ts
│   └── ...
├── utils/            # 工具函数
│   ├── request.ts    # Axios 封装
│   ├── auth.ts       # 认证工具
│   └── ...
└── types/            # TypeScript 类型定义
```

### 核心功能模块

1. **用户认证** - 登录、登出、Token 刷新
2. **动态路由** - 根据用户角色动态加载路由和菜单
3. **权限控制** - 按钮级权限指令（v-permission）
4. **需求管理** - 需求列表、详情、创建、编辑、状态流转
5. **任务管理** - 任务列表、分配、工时填报
6. **事项管理** - 事项创建、处理、验证
7. **数据看板** - 多角色看板、图表展示
8. **系统管理** - 用户管理、项目管理、工作流配置

### 状态管理（Pinia）

- `userStore` - 用户信息、权限
- `menuStore` - 菜单、路由
- `requirementStore` - 需求数据缓存
- `taskStore` - 任务数据缓存

---

## 第五部分：安全和权限设计

### JWT 认证流程

1. 用户登录 → 后端验证 → 返回 JWT Token
2. 前端存储 Token（localStorage）
3. 每次请求携带 Token（Authorization: Bearer <token>）
4. 后端验证 Token → 解析用户信息 → 执行业务逻辑

### RBAC 权限模型

**角色定义：**
- BU负责人 - 全局数据访问
- 项目经理 - 按项目隔离
- 技术经理 - 按项目隔离（通过 project_member）
- 产品经理 - 按业务线
- 前端研发/后端研发/测试/UI设计 - 按任务分配

### 数据权限隔离

**实现方式：**
- MyBatis Plus 数据权限拦截器
- 根据用户角色自动添加 WHERE 条件
- 项目经理只能看到自己参与的项目数据
- 技术经理只能看到自己参与的项目数据

**示例：**
```java
// 项目经理查询需求时，自动添加条件
SELECT * FROM requirement 
WHERE project_id IN (
    SELECT project_id FROM project_member WHERE user_id = ?
)
```

### 前端权限控制

**路由守卫：**
- 登录验证
- 角色验证
- 动态路由加载

**按钮级权限：**
```vue
<el-button v-permission="['BU负责人', '项目经理']">
  创建需求
</el-button>
```

### 缓存策略（Redis）

**缓存内容：**
- 用户信息（30分钟）
- 权限配置（1小时）
- 工作流配置（1小时）
- 菜单路由（1小时）

**缓存更新：**
- 用户信息变更 → 清除用户缓存
- 权限配置变更 → 清除所有权限缓存
- 工作流配置变更 → 清除工作流缓存

---

## 第六部分：工作流引擎设计

### 自研轻量级状态机

**核心组件：**
1. **WorkflowEngine** - 状态流转引擎
2. **WorkflowValidator** - 权限和前置条件验证
3. **WorkflowConfig** - 配置管理（从数据库加载）

### 状态流转逻辑

```java
public class WorkflowEngine {
    // 执行状态流转
    public void transition(Requirement req, String toStatus, User user) {
        // 1. 加载工作流配置
        WorkflowConfig config = loadConfig(req.getType(), req.getStatus(), toStatus);
        
        // 2. 验证角色权限
        if (!config.getAllowedRoles().contains(user.getRole())) {
            throw new PermissionDeniedException();
        }
        
        // 3. 验证前置条件
        if (!validateCondition(req, config.getCondition())) {
            throw new ConditionNotMetException();
        }
        
        // 4. 执行状态流转
        req.setStatus(toStatus);
        requirementMapper.updateById(req);
        
        // 5. 触发后置动作（如自动流转）
        executePostActions(req, toStatus);
    }
}
```

### 前置条件示例

**设计中 → 待确认：**
```json
{
  "condition": "all_design_completed",
  "description": "所有设计工作已完成"
}
```

**开发中 → 测试中：**
```json
{
  "condition": "all_tasks_completed",
  "description": "所有开发任务已完成"
}
```

### 工作流配置管理

**初始化配置：**
- 通过 Flyway 脚本初始化默认配置
- 系统管理员可在前端修改配置
- 配置变更后清除 Redis 缓存

---

## 第七部分：测试策略

### 测试覆盖率目标：80%+

### 后端测试

**1. 单元测试（JUnit 5 + Mockito）**
- Service 层业务逻辑测试
- 工具类测试
- 工作流引擎测试
- 覆盖率目标：80%+

**2. 集成测试（Spring Boot Test + Testcontainers）**
- Controller 层 API 测试
- 数据库操作测试（使用 Testcontainers MySQL）
- Redis 缓存测试
- MinIO 文件上传测试

**3. API 测试（Knife4j + Postman）**
- 所有 API 端点手动测试
- 导出 Postman Collection

### 前端测试

**1. 单元测试（Vitest + Vue Test Utils）**
- 组件测试
- 工具函数测试
- Store 测试
- 覆盖率目标：80%+

**2. E2E 测试（Playwright）**
- 登录流程
- 需求创建和状态流转
- 任务分配和工时填报
- 关键业务流程

### 测试环境

**Docker Compose 测试环境：**
- 独立的测试数据库
- 独立的 Redis 实例
- 独立的 MinIO 实例
- 测试数据自动初始化和清理

---

## 第八部分：实施计划

### 方案 B：前后端分离并行开发

**总周期：16-20 周**

### 阶段 1：后端 API 开发（8-10 周）

**Week 1-2: 基础设施**
- Docker Compose 环境搭建
- Spring Boot 项目初始化
- 数据库 Schema 设计和 Flyway 迁移
- JWT 认证和 Spring Security 配置

**Week 3-4: 组织架构模块**
- 业务线、项目、用户 CRUD API
- 项目成员管理 API
- 客户联系人管理 API
- RBAC 权限系统

**Week 5-6: 需求管理核心**
- 需求 CRUD API
- 需求评估 API
- BU 决策 API
- 需求状态流转

**Week 7-8: 需求设计和任务**
- 设计工作管理 API
- 任务管理 API
- 工时记录 API
- 自动流转逻辑

**Week 9-10: 事项、工作流、统计**
- 事项管理 API
- 工作流引擎实现
- 数据统计和看板 API
- 文件上传（MinIO）

### 阶段 2：前端开发（6-8 周）

**Week 11-12: 基础框架**
- Vue 3 项目初始化
- 路由和状态管理
- 登录和认证
- 动态路由和菜单

**Week 13-14: 核心页面**
- 需求管理页面
- 任务管理页面
- 工时填报页面

**Week 15-16: 高级功能**
- 事项管理页面
- 数据看板页面
- 系统管理页面

**Week 17-18: 优化和测试**
- UI/UX 优化
- 性能优化
- E2E 测试

### 阶段 3：集成测试和部署（2 周）

**Week 19-20:**
- 前后端集成测试
- Bug 修复
- 部署到测试环境
- 用户验收测试

### 关键里程碑

- Week 2: 基础设施就绪
- Week 10: 后端 API 全部完成
- Week 18: 前端页面全部完成
- Week 20: 系统上线

### 验收标准

- 所有 API 端点正常工作
- 测试覆盖率 >= 80%
- 前端所有页面功能完整
- 性能满足要求（响应时间 < 2s）
- 安全测试通过
- 用户验收通过

---

## 设计完成

本设计文档涵盖了电商BU内部管理系统的完整架构、技术选型、数据库设计、安全权限、工作流引擎、测试策略和实施计划。

**下一步：**
根据 Beads + Superpowers 工作流，将在 Beads 中创建具体的实施任务，按照未阻塞的最小任务逐步实现。
