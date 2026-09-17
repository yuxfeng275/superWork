# BU Management System - Backend

电商BU内部管理系统 - 后端服务

## 技术栈

- Java 17
- Spring Boot 3.2.4
- MyBatis Plus 3.5.5
- Spring Security 6.x
- MySQL 8.0
- Redis 7.x
- MinIO
- Flyway
- Knife4j (API 文档)

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.6+
- MySQL 8.0
- Redis 7.x
- MinIO

### 启动步骤

1. **启动基础设施**

```bash
cd ../docker
docker-compose up -d mysql redis minio
```

2. **配置环境变量（可选）**

复制 `application-dev.yml` 并根据需要修改配置。

3. **启动应用**

```bash
mvn spring-boot:run
```

或者使用 IDE 运行 `BuManagementApplication`。

4. **访问 API 文档**

http://localhost:8081/doc.html

## 项目结构

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/bu/management/
│   │   │   ├── config/           # 配置类
│   │   │   ├── controller/       # 控制器
│   │   │   ├── service/          # 业务逻辑
│   │   │   ├── mapper/           # MyBatis Mapper
│   │   │   ├── entity/           # 实体类
│   │   │   ├── dto/              # 数据传输对象
│   │   │   ├── vo/               # 视图对象
│   │   │   ├── enums/            # 枚举
│   │   │   ├── exception/        # 异常处理
│   │   │   ├── security/         # 安全配置
│   │   │   ├── workflow/         # 工作流引擎
│   │   │   └── util/             # 工具类
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/     # Flyway 迁移脚本
│   └── test/                     # 测试代码
├── pom.xml
├── Dockerfile
└── README.md
```

## 开发命令

```bash
# 编译
mvn clean compile

# 运行测试
mvn test

# 打包
mvn clean package

# 跳过测试打包
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 指定环境运行
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

## Docker 构建

```bash
# 构建镜像
docker build -t bu-management-backend:latest .

# 运行容器
docker run -d \
  -p 8081:8081 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e MYSQL_HOST=mysql \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DATABASE=bu_management \
  -e MYSQL_USER=bu_admin \
  -e MYSQL_PASSWORD=bu_admin123 \
  -e REDIS_HOST=redis \
  -e REDIS_PORT=6379 \
  -e MINIO_ENDPOINT=http://minio:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin123 \
  --name bu-management-backend \
  bu-management-backend:latest
```

## API 文档

启动应用后访问：http://localhost:8081/doc.html

## 数据库迁移

使用 Flyway 管理数据库版本：

- 迁移脚本位置：`src/main/resources/db/migration/`
- 命名规则：`V{version}__{description}.sql`
- 示例：`V1__init_schema.sql`

应用启动时会自动执行迁移。

## 测试

```bash
# 运行所有测试
mvn test

# 运行单个测试类
mvn test -Dtest=UserServiceTest

# 生成测试覆盖率报告
mvn test jacoco:report
```

## 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| SPRING_PROFILES_ACTIVE | 运行环境 | dev |
| MYSQL_HOST | MySQL 主机 | localhost |
| MYSQL_PORT | MySQL 端口 | 3306 |
| MYSQL_DATABASE | 数据库名 | bu_management |
| MYSQL_USER | 数据库用户 | bu_admin |
| MYSQL_PASSWORD | 数据库密码 | bu_admin123 |
| REDIS_HOST | Redis 主机 | localhost |
| REDIS_PORT | Redis 端口 | 6379 |
| MINIO_ENDPOINT | MinIO 地址 | http://localhost:9000 |
| MINIO_ACCESS_KEY | MinIO 访问密钥 | minioadmin |
| MINIO_SECRET_KEY | MinIO 密钥 | minioadmin123 |
| JWT_SECRET | JWT 密钥 | (需要设置) |

## 注意事项

1. 生产环境必须修改默认密码
2. JWT_SECRET 必须通过环境变量设置
3. 确保数据库字符集为 utf8mb4
4. Redis 建议配置持久化
5. 定期备份数据库
