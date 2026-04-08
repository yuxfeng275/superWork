# 阶段 2 设计规范：测试、原型、前端和部署

**项目：** 电商BU内部管理系统
**版本：** v2.0
**日期：** 2026-04-03
**状态：** 设计中

---

## 1. 概述

本文档定义了电商BU内部管理系统阶段2的设计规范，涵盖测试质量保证、原型设计、前端开发和部署实施。

### 1.1 背景

阶段1已完成项目初始化、数据库配置和后端API开发。当前系统包含：
- Spring Boot 3.2.4 + MyBatis Plus 3.5.5
- MySQL 8.0 数据库（16张表）
- JWT 认证 + BCrypt 密码加密
- 79个 REST API 接口（17个控制器）

### 1.2 阶段目标

阶段2目标是建立完整的测试体系、设计用户界面原型、开发前端应用并完成生产部署。

---

## 2. 阶段 2：测试与质量保障（2-3天）

### 2.1 测试架构

**测试金字塔：**
```
        /\
       /  \
      / E2E \        ← 5-10 个关键流程测试
     /--------\
    / Integration \  ← 30-50 个 API 和数据库集成测试
   /--------------\
  /   Unit Tests   \ ← 200+ 个单元测试
 /------------------\
```

**技术栈：**
- 单元测试：JUnit 5 + Mockito
- 集成测试：Spring Boot Test + Testcontainers
- E2E测试：Playwright（Vue应用）
- 覆盖率：JaCoCo（目标 80%+）

### 2.2 单元测试

**Service 层测试（17个Service）：**
- 每个Service类对应一个测试类
- 使用Mockito模拟依赖
- 测试覆盖率目标：80%+

**Controller 层测试（17个Controller）：**
- 使用 @WebMvcTest 注解
- 模拟Service层依赖
- 验证请求路由、参数绑定、响应格式

**示例测试类结构：**
```java
@SpringBootTest
class RequirementServiceTest {
    @MockBean
    private RequirementMapper requirementMapper;
    
    @Autowired
    private RequirementService requirementService;
    
    @Test
    void shouldCreateRequirementSuccessfully() {
        // given
        Requirement requirement = new Requirement();
        requirement.setTitle("Test Requirement");
        
        // when
        Requirement result = requirementService.create(requirement);
        
        // then
        assertNotNull(result.getId());
    }
}
```

### 2.3 集成测试

**使用 Testcontainers：**
- MySQL 8.0 容器用于测试
- Redis 容器（可选）
- 每次测试启动干净数据库

**测试范围：**
- MyBatis Plus CRUD操作
- Service层业务逻辑
- Controller层 API 端点
- 事务管理

### 2.4 E2E 测试

**测试场景（5个核心流程）：**
1. 用户登录 → 创建需求 → 审核需求
2. 创建任务 → 记录工时 → 查看统计
3. 需求全生命周期流转
4. 组织架构管理（CRUD）
5. 数据统计看板加载

**Playwright 配置：**
```javascript
const config = {
  testDir: './e2e',
  baseURL: 'http://localhost:5173',
  retries: 2,
  use: {
    trace: 'on-first-retry',
  },
};
```

### 2.5 质量门槛

| 指标 | 目标 |
|------|------|
| 单元测试覆盖率 | 80%+ |
| 集成测试覆盖率 | 70%+ |
| E2E 测试通过率 | 100% |
| 严重漏洞 | 0 |
| 高危漏洞 | 0 |

---

## 3. 阶段 2.5：原型设计（1天）

### 3.1 设计目标

在正式开始前端开发前，通过可交互的原型让用户确认页面布局和交互流程。

### 3.2 原型工具

**工具选择：HTML 原型**

**理由：**
- 使用 Ant Design Vue 官方样式，视觉效果接近最终产品
- 可在浏览器中直接交互体验
- 快速迭代修改
- 无需额外设计工具

### 3.3 页面清单

| 序号 | 页面名称 | 优先级 | 说明 |
|------|----------|--------|------|
| 1 | 登录页面 | P0 | 用户认证入口 |
| 2 | 需求列表页面 | P0 | 需求CRUD+筛选搜索 |
| 3 | 需求详情页面 | P0 | 需求全生命周期管理 |
| 4 | 任务管理页面 | P0 | 任务创建、分配、工时记录 |
| 5 | 数据统计看板 | P1 | 人效分析、业务线健康度 |
| 6 | 组织架构管理 | P2 | 业务线、项目、成员管理 |

### 3.4 原型文件结构

```
frontend-prototype/
├── index.html          # 入口页面（导航到各页面）
├── login.html          # 登录页面
├── requirements.html   # 需求列表页面
├── requirement-detail.html  # 需求详情页面
├── tasks.html          # 任务管理页面
├── statistics.html     # 数据统计看板
└── css/
    └── style.css       # 共享样式
```

### 3.5 原型设计确认流程

1. 我创建 HTML 原型文件
2. 你在浏览器中打开查看
3. 确认页面布局和交互流程
4. 如需修改，反馈给我调整
5. 确认后开始前端开发

### 3.6 页面详细说明

#### 3.6.1 登录页面（login.html）

**布局：**
- 顶部：系统标题"电商BU内部管理系统"
- 中间：登录表单卡片（居中）
  - 用户名输入框
  - 密码输入框
  - 记住我复选框
  - 登录按钮
- 底部：版权信息

**交互：**
- 表单验证（必填、格式）
- 登录按钮加载状态
- 错误提示信息

#### 3.6.2 需求列表页面（requirements.html）

**布局：**
- 顶部：导航栏（logo、用户信息、退出）
- 左侧：菜单栏（收起/展开）
  - 首页
  - 需求管理（高亮）
  - 任务管理
  - 统计看板
  - 组织架构
- 右侧：内容区
  - 页面标题 + 新建按钮
  - 筛选条件行（状态、业务线、项目、日期范围）
  - 搜索框
  - 需求表格
    - 列：ID、标题、业务线、项目、状态、负责人、创建时间、操作
  - 分页器

**交互：**
- 点击新建 → 打开新建需求弹窗
- 点击行 → 跳转到需求详情
- 筛选条件变化 → 刷新列表
- 分页切换 → 刷新列表

#### 3.6.3 需求详情页面（requirement-detail.html）

**布局：**
- 顶部：导航栏
- 左侧：菜单栏
- 右侧：内容区
  - 面包屑导航
  - 需求标题 + 状态标签
  - 操作按钮（编辑、删除、流转）
  - Tab页签
    - 基本信息（标题、描述、业务线、项目、类型、优先级等）
    - 评估信息（预估工时、评估人、评估时间）
    - 设计信息（设计类型、设计师、设计状态）
    - 确认信息（确认结果、确认人、确认时间）
    - 开发信息（开发人员、开始时间、预计结束）
    - 测试信息（测试人员、测试结果）
    - 上线信息（上线时间、上线状态）
    - 交付信息（交付状态、交付时间）
    - 附件列表
    - 活动日志

**交互：**
- 点击编辑 → 表单变为可编辑状态
- 选择状态流转 → 显示流转确认弹窗
- 上传附件 → 显示上传进度
- 查看日志 → 展开活动时间线

#### 3.6.4 任务管理页面（tasks.html）

**布局：**
- 顶部：导航栏
- 左侧：菜单栏
- 右侧：内容区
  - 页面标题 + 新建按钮
  - 筛选条件行（项目、负责人、状态、日期范围）
  - 搜索框
  - 任务看板（可选）或 任务表格
    - 列：ID、任务名称、所属需求、项目、负责人、预估工时、实际工时、状态、截止日期、操作
  - 分页器

**交互：**
- 拖拽任务卡片 → 更新状态（看板视图）
- 点击任务 → 打开任务详情弹窗
- 记录工时 → 打开工时记录弹窗

#### 3.6.5 数据统计看板（statistics.html）

**布局：**
- 顶部：导航栏
- 左侧：菜单栏（统计看板高亮）
- 右侧：内容区
  - 页面标题
  - 统计卡片行
    - 总需求数
    - 进行中需求数
    - 总任务数
    - 总工时数
  - 图表区
    - 需求状态分布（饼图）
    - 人效分析（柱状图）
    - 业务线健康度（雷达图）
    - 月度趋势（折线图）
  - 数据表格
    - 业务线统计表
    - 项目统计表

**交互：**
- 选择日期范围 → 刷新图表数据
- 点击图表 → 显示详细数据
- 导出数据 → 生成Excel

#### 3.6.6 组织架构管理页面（organization.html）

**布局：**
- 顶部：导航栏
- 左侧：菜单栏
- 右侧：内容区
  - Tab页签：业务线 | 项目 | 成员
  - 业务线Tab：
    - 业务线列表（树形结构）
    - 新建业务线按钮
  - 项目Tab：
    - 项目列表（树形结构，按业务线分组）
    - 新建项目按钮
  - 成员Tab：
    - 成员列表（表格）
    - 筛选（业务线、项目、角色）
    - 新建成员按钮

**交互：**
- 点击业务线 → 展开/折叠子项目
- 点击编辑 → 打开编辑弹窗
- 拖拽 → 调整父子关系

---

## 4. 阶段 3：前端开发（4-5天）

### 4.1 技术栈

**核心框架：**
- Vue 3.4+ (Composition API + `<script setup>`)
- TypeScript 5.x
- Vite 5.x
- Ant Design Vue 5.x
- Pinia (状态管理)
- Vue Router 4.x

**辅助库：**
- Axios (HTTP客户端)
- Day.js (日期处理)
- ExcelJS (Excel导出)
- ECharts (图表)

### 4.2 项目结构

```
frontend/
├── public/
├── src/
│   ├── api/           # API 接口定义
│   │   ├── requirement.ts
│   │   ├── task.ts
│   │   ├── statistics.ts
│   │   └── ...
│   ├── assets/        # 静态资源
│   ├── components/    # 公共组件
│   │   ├── Layout/
│   │   ├── Table/
│   │   └── Form/
│   ├── composables/   # 组合式函数
│   ├── layouts/       # 布局组件
│   ├── router/        # 路由配置
│   ├── stores/        # Pinia 状态管理
│   ├── types/         # TypeScript 类型定义
│   ├── utils/         # 工具函数
│   ├── views/         # 页面组件
│   │   ├── Login/
│   │   ├── Requirements/
│   │   ├── Tasks/
│   │   ├── Statistics/
│   │   └── Organization/
│   ├── App.vue
│   └── main.ts
├── .env.example
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

### 4.3 开发顺序

**第1天：项目初始化**
- 初始化 Vue 3 + TypeScript + Vite 项目
- 配置 Ant Design Vue
- 配置路由和状态管理
- 配置 API 请求封装（Axios + 拦截器）

**第2-3天：核心页面**
- 登录页面
- 需求列表页面
- 需求详情页面

**第4天：重要页面**
- 任务管理页面
- 数据统计看板

**第5天：辅助页面 + 优化**
- 组织架构管理
- 响应式优化
- 错误处理优化

### 4.4 页面组件清单

| 页面 | 组件 | 复杂度 |
|------|------|--------|
| 登录 | LoginView | 低 |
| 需求列表 | RequirementListView | 高 |
| 需求详情 | RequirementDetailView | 高 |
| 任务管理 | TaskListView / TaskBoardView | 中 |
| 统计看板 | StatisticsDashboardView | 中 |
| 组织架构 | OrganizationView | 中 |

---

## 5. 阶段 4：部署与优化（2-3天）

### 5.1 Docker 容器化

**后端 Dockerfile（多阶段构建）：**
```dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "app.jar"]
```

**前端 Dockerfile（多阶段构建）：**
```dockerfile
# 构建阶段
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json .
RUN npm ci
COPY . .
RUN npm run build

# 运行阶段
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**Nginx 配置（SPA 支持）：**
```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://backend:8080/;
    }
}
```

### 5.2 Docker Compose 编排

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: bu_management
    volumes:
      - mysql-data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d
    ports:
      - "3306:3306"

  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data

  backend:
    build: ./backend
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/bu_management
      SPRING_REDIS_HOST: redis
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    depends_on:
      - backend
    ports:
      - "80:80"

volumes:
  mysql-data:
  redis-data:
```

### 5.3 环境配置

**环境变量文件：**
- `.env.example` - 所有变量的模板
- `.env.development` - 本地开发
- `.env.production` - 生产环境（不提交到git）

**后端配置（application-prod.yml）：**
```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000
```

**前端配置（.env.production）：**
```
VITE_API_BASE_URL=/api
VITE_APP_TITLE=电商BU内部管理系统
```

### 5.4 部署检查清单

**部署前：**
- [ ] 数据库迁移脚本已准备
- [ ] 环境变量已配置
- [ ] SSL 证书已准备（如需HTTPS）
- [ ] 备份已创建

**部署后：**
- [ ] 健康检查端点正常
- [ ] 登录功能正常
- [ ] 核心业务流程正常
- [ ] 监控和日志已配置

### 5.5 性能优化

**后端优化：**
- Redis 缓存（用户信息、项目列表）
- 数据库连接池配置（HikariCP）
- API 响应压缩（Gzip）
- 索引优化（基于慢查询分析）

**前端优化：**
- 路由懒加载
- 组件懒加载
- 图片懒加载
- 产物压缩（terser）

**监控：**
- Spring Boot Actuator
- 应用日志（JSON格式）
- 数据库慢查询日志
- 前端错误追踪（可选Sentry）

---

## 6. 总体时间估算

| 阶段 | 工作内容 | 预计时间 |
|------|----------|----------|
| 阶段2 | 测试与质量保障 | 2-3天 |
| 阶段2.5 | 原型设计 | 1天 |
| 阶段3 | 前端开发 | 4-5天 |
| 阶段4 | 部署与优化 | 2-3天 |
| **总计** | | **9-12天** |

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| 原型确认返工 | 时间延迟 | 先确认核心页面流程 |
| 测试覆盖率不达标 | 质量风险 | 优先保证核心业务测试 |
| 前端样式调整 | 时间延迟 | 使用Ant Design Vue减少自定义 |
| 部署问题 | 上线延迟 | 提前在测试环境验证 |

---

## 8. 确认事项

本文档包含以下部分，需逐一确认：

- [x] 2. 测试架构和技术栈
- [x] 2.2-2.4 测试类型和范围
- [x] 2.5 质量门槛
- [x] 3. 原型设计方案（HTML原型）
- [x] 3.3-3.6 页面清单和详细说明
- [ ] 4. 前端技术栈和项目结构
- [ ] 4.3 开发顺序
- [ ] 5. Docker容器化方案
- [ ] 5.2 Docker Compose配置
- [ ] 5.3-5.5 环境配置和优化
- [ ] 6. 时间估算
- [ ] 7. 风险与对策

---

**下一步：**
1. 确认本文档内容
2. 我将更新 Beads 任务状态
3. 开始阶段2实施（测试 → 原型 → 前端 → 部署）
