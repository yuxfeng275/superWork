# 电商BU内部管理系统

电商BU内部管理系统 - 需求全生命周期管理平台

## 项目简介

本系统是一个完整的需求管理平台，覆盖需求从创建到验收的全生命周期，包括评估、设计、开发、测试、上线、交付、验收等各个环节。

### 核心功能

- **需求全生命周期管理**：创建 → 评估 → 设计 → 确认 → 开发 → 测试 → 上线 → 交付 → 验收
- **组织架构管理**：业务线、项目、用户、项目成员、客户联系人
- **设计工作管理**：原型设计、UI设计、技术方案设计
- **任务和工时管理**：任务分配、工时记录、自动汇总
- **事项管理**：问题、风险、变更跟踪
- **数据统计**：需求统计、任务统计、工时分析

## 技术栈

### 后端
- **框架**：Spring Boot 3.2.4
- **ORM**：MyBatis Plus 3.5.5
- **数据库**：MySQL 8.0
- **认证**：JWT + BCrypt
- **API 文档**：Swagger/OpenAPI 3.0
- **构建工具**：Maven
- **Java 版本**：17

### 前端（待开发）
- Vue 3 + TypeScript
- Ant Design Vue
- Vite

## 项目结构

```
superWork-claude-sp/
├── backend/                    # 后端项目
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/bu/management/
│   │   │   │       ├── controller/      # 控制器层（17个）
│   │   │   │       ├── service/         # 服务层（17个）
│   │   │   │       ├── mapper/          # 数据访问层（16个）
│   │   │   │       ├── entity/          # 实体类（16个）
│   │   │   │       ├── dto/             # 数据传输对象（30+个）
│   │   │   │       ├── vo/              # 视图对象（5个）
│   │   │   │       ├── config/          # 配置类
│   │   │   │       ├── middleware/      # 中间件
│   │   │   │       └── utils/           # 工具类
│   │   │   └── resources/
│   │   │       ├── application.yml      # 配置文件
│   │   │       └── db/
│   │   │           └── schema.sql       # 数据库初始化脚本
│   │   └── test/                        # 测试代码
│   └── pom.xml                          # Maven 配置
├── frontend/                   # 前端项目（待开发）
├── docker/                     # Docker 配置
│   └── docker-compose.yml
└── docs/                       # 项目文档
    ├── PROJECT_COMPLETE.md     # 项目完成报告
    ├── RESUME.md               # 停工报告
    └── superpowers/
        └── specs/              # 设计文档
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- MySQL 8.0+
- Redis 6.0+（可选）

### 1. 克隆项目

```bash
git clone https://github.com/S1ow/superWork.git
cd superWork-claude-sp
```

### 2. 配置数据库

```bash
# 创建数据库
mysql -u root -p < backend/src/main/resources/db/schema.sql

# 或手动创建
mysql -u root -p
CREATE DATABASE bu_management DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 配置应用

编辑 `backend/src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/bu_management?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
```

### 4. 启动后端

```bash
cd backend
mvn clean install
mvn spring-boot:run
```

后端服务将在 `http://localhost:8080` 启动。

### 5. 访问 API 文档

启动后访问 Swagger UI：
```
http://localhost:8080/swagger-ui.html
```

### 6. 默认账号

- 用户名：`admin`
- 密码：`admin123`

## API 端点

### 认证模块（2个）
- `POST /api/auth/register` - 用户注册
- `POST /api/auth/login` - 用户登录

### 组织架构模块（25个）
- **业务线**：5个端点（CRUD + 列表）
- **项目**：6个端点（CRUD + 列表 + 项目树）
- **用户**：6个端点（CRUD + 列表 + 分页）
- **项目成员**：4个端点（添加、删除、查询、列表）
- **客户联系人**：4个端点（CRUD）

### 需求管理模块（52个）
- **需求基础**：6个端点（CRUD + 列表 + 分页）
- **需求评估**：2个端点（提交评估、查询评估）
- **BU决策**：1个端点（提交决策）
- **需求状态流转**：2个端点（查询流转信息、验证流转）
- **需求设计**：3个端点（创建、更新、查询）
- **设计工作记录**：5个端点（CRUD + 列表）
- **需求确认**：2个端点（创建确认、查询确认）
- **需求交付**：3个端点（创建交付、验收、查询）
- **任务管理**：6个端点（CRUD + 列表 + 分页）
- **工时记录**：6个端点（CRUD + 列表 + 分页）
- **事项管理**：6个端点（CRUD + 列表 + 分页）
- **附件管理**：4个端点（创建、查询、列表、删除）
- **数据统计**：2个端点（需求统计、任务统计）

**总计：79 个 REST 端点**

## 数据库设计

### 数据库表（16张）

#### 组织架构模块（5张）
1. `business_line` - 业务线表
2. `project` - 项目表
3. `user` - 用户表
4. `project_member` - 项目成员表
5. `customer_contact` - 客户联系人表

#### 需求管理模块（11张）
6. `requirement` - 需求表
7. `requirement_evaluation` - 需求评估表
8. `requirement_design` - 需求设计汇总表
9. `design_work_log` - 设计工作记录表
10. `requirement_confirmation` - 需求确认表
11. `requirement_delivery` - 需求交付表
12. `task` - 任务表
13. `work_log` - 工时记录表
14. `issue` - 事项表
15. `attachment` - 附件表

详细的数据库设计请查看：`backend/src/main/resources/db/schema.sql`

## 需求状态流转

```
项目需求：
创建 → 待评估 → 评估中 → [已拒绝 | 待设计] → 设计中 → 待确认(客户) 
→ 开发中 → 测试中 → 待上线 → 已上线 → 已交付 → 已验收

产品需求：
创建 → 待评估 → 评估中 → [已拒绝 | 待设计] → 设计中 → 待确认(内部) 
→ 开发中 → 测试中 → 待上线 → 已上线
```

### 自动状态流转规则

1. **提交评估** → 待评估 → 评估中
2. **BU决策通过** → 评估中 → 待设计
3. **BU决策拒绝** → 评估中 → 已拒绝
4. **创建设计** → 待设计 → 设计中
5. **设计完成**（三种设计全部完成）→ 设计中 → 待确认
6. **需求确认** → 待确认 → 开发中
7. **创建交付** → 已上线 → 已交付
8. **验收需求** → 已交付 → 已验收

## 开发指南

### 代码结构

```
Controller → Service → Mapper → Database
    ↓          ↓
   DTO        Entity
    ↓
   VO
```

### 命名规范

- **Controller**：`XxxController`
- **Service**：`XxxService`
- **Mapper**：`XxxMapper`
- **Entity**：`Xxx`
- **DTO**：`CreateXxxDTO`, `UpdateXxxDTO`
- **VO**：`XxxVO`, `XxxStatistics`

### 统一响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {},
  "timestamp": "2026-04-03T14:00:00"
}
```

### 分页响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [],
    "total": 100,
    "size": 10,
    "current": 1,
    "pages": 10
  },
  "timestamp": "2026-04-03T14:00:00"
}
```

## 测试

```bash
# 运行所有测试
mvn test

# 运行测试并生成覆盖率报告
mvn test jacoco:report
```

## 部署

### Docker 部署

```bash
# 构建镜像
docker build -t bu-management:latest .

# 启动服务
docker-compose up -d
```

### 传统部署

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/management-0.0.1-SNAPSHOT.jar
```

## 项目统计

- **Java 文件**：106 个
- **代码行数**：约 8000+ 行
- **REST 端点**：79 个
- **数据库表**：16 张
- **Git 提交**：23 次

## 后续优化

- [ ] 添加单元测试（目标覆盖率 80%+）
- [ ] 添加集成测试
- [ ] 实现文件上传功能
- [ ] 添加 Redis 缓存
- [ ] 实现工作流引擎
- [ ] 添加消息通知
- [ ] 实现数据导出（Excel）
- [ ] 添加操作日志
- [ ] 前端开发

## 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 许可证

MIT License

## 联系方式

- 项目地址：https://github.com/S1ow/superWork
- 问题反馈：https://github.com/S1ow/superWork/issues

## 更新日志

### v1.0.0 (2026-04-03)

- ✅ 完成后端 API 开发（79个端点）
- ✅ 实现需求全生命周期管理
- ✅ 实现组织架构管理
- ✅ 实现任务和工时管理
- ✅ 实现事项管理
- ✅ 实现数据统计功能
- ✅ 完成数据库设计（16张表）
- ✅ 集成 Swagger API 文档
- ✅ 实现 JWT 认证

---

**开发时间：** 2026-04-02 ~ 2026-04-03  
**项目状态：** ✅ 后端完成，前端待开发
