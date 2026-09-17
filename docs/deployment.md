# 部署文档 — 电商BU内部管理系统

> 更新时间：2026-04-24

---

## 目录

1. [环境要求](#环境要求)
2. [服务架构](#服务架构)
3. [快速部署](#快速部署)
4. [访问地址](#访问地址)
5. [服务配置说明](#服务配置说明)
6. [常用运维命令](#常用运维命令)
7. [故障排查](#故障排查)
8. [测试账号](#测试账号)

---

## 环境要求

| 依赖 | 最低版本 | 备注 |
|------|---------|------|
| Docker | 24.x+ | `docker --version` |
| Docker Compose | v2.x+ | `docker compose version` |
| 可用内存 | 4GB+ | MySQL + JVM 开销较大 |
| 可用磁盘 | 10GB+ | 含 Docker 镜像 |

---

## 服务架构

```
浏览器
  │
  ▼
Nginx :80          ← 统一入口，反向代理
  ├── /            → Frontend :80  (Vue 3 静态页面)
  ├── /api/        → Backend  :8081 (Spring Boot)
  └── /doc.html    → Backend  :8081 (Knife4j API 文档)

Backend :8081
  ├── MySQL  :3306
  ├── Redis  :6379
  └── MinIO  :9000
```

### 容器列表

| 容器名 | 镜像 | 端口映射 | 说明 |
|--------|------|---------|------|
| bu-management-nginx | nginx:alpine | 80:80 | 统一入口 |
| bu-management-backend | 本地构建 | 8081:8081 | Spring Boot API |
| bu-management-frontend | 本地构建 | 8080:80 | Vue 3 前端 |
| bu-management-mysql | mysql:8.0 | 3306:3306 | 数据库 |
| bu-management-redis | redis:7-alpine | 6379:6379 | 缓存 |
| bu-management-minio | minio/minio | 9000-9001 | 对象存储 |

---

## 快速部署

### 第一次部署

```bash
# 1. 进入 docker 目录
cd docker

# 2. 构建并启动所有服务（首次构建约 5-10 分钟）
docker compose up -d --build

# 3. 查看启动日志
docker compose logs -f
```

### 更新部署

```bash
cd docker

# 重新构建并启动（仅重建有变更的服务）
docker compose up -d --build backend    # 仅更新后端
docker compose up -d --build frontend   # 仅更新前端
docker compose up -d --build           # 更新所有服务
```

### 停止服务

```bash
cd docker

# 停止所有容器（保留数据）
docker compose stop

# 停止并删除容器（保留数据卷）
docker compose down

# 完全清除（含数据，慎用！）
docker compose down -v
```

---

## 访问地址

| 服务 | 地址 | 说明 |
|------|------|------|
| **前端应用（推荐）** | http://localhost:8000 | 通过 Nginx 访问，含 API 代理 |
| 前端直连 | http://localhost:8080 | 前端容器直连 |
| 后端 API | http://localhost:8081 | Spring Boot 直连（本地进程） |
| **API 文档** | http://localhost:8081/doc.html | Knife4j 接口文档 |
| MinIO 控制台 | http://localhost:9001 | 对象存储管理 |

> 推荐使用 **http://localhost:8000** 访问，通过 Nginx 统一代理前后端。

---

## 服务配置说明

### 数据库

| 参数 | 值 |
|------|----|
| Host | localhost:3306 |
| 数据库名 | bu_management |
| 用户名 | bu_admin |
| 密码 | bu_admin123 |
| Root 密码 | root123456 |

### Redis

| 参数 | 值 |
|------|----|
| Host | localhost:6379 |
| 密码 | 无 |

### MinIO

| 参数 | 值 |
|------|----|
| API 端口 | 9000 |
| 控制台端口 | 9001 |
| AccessKey | minioadmin |
| SecretKey | minioadmin123 |

### 数据持久化目录

```
docker/
├── mysql/
│   ├── data/     ← 数据库文件（持久化）
│   ├── conf/     ← MySQL 配置
│   └── init/     ← 初始化脚本（首次启动执行）
├── redis/
│   └── data/     ← Redis 持久化数据
└── minio/
    └── data/     ← 对象存储文件
```

---

## 常用运维命令

```bash
# 查看所有容器状态
docker compose ps

# 查看指定服务日志
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f mysql

# 进入容器
docker exec -it bu-management-backend sh
docker exec -it bu-management-mysql mysql -u bu_admin -pbu_admin123 bu_management

# 重启单个服务
docker compose restart backend

# 查看资源使用
docker stats bu-management-backend bu-management-mysql

# 备份数据库
docker exec bu-management-mysql mysqldump -u bu_admin -pbu_admin123 bu_management > backup_$(date +%Y%m%d).sql

# 恢复数据库
docker exec -i bu-management-mysql mysql -u bu_admin -pbu_admin123 bu_management < backup.sql
```

---

## 故障排查

### 后端启动失败 — 数据库连接错误

```bash
# 检查 MySQL 是否就绪
docker exec bu-management-mysql mysqladmin ping -u bu_admin -pbu_admin123

# 查看后端启动日志
docker compose logs backend | grep -E "ERROR|WARN|Started|Failed"
```

### 前端无法访问 API

```bash
# 检查 Nginx 代理配置
docker exec bu-management-nginx nginx -t

# 检查后端是否运行
curl -s http://localhost:8081/actuator/health
```

### 端口冲突

若 3306/6379/80 端口已被占用，修改 `docker-compose.yml` 中的端口映射：

```yaml
ports:
  - "13306:3306"   # 改为 13306
```

### 查看构建缓存 / 强制重建

```bash
# 清除构建缓存，完全重建
docker compose build --no-cache backend
docker compose up -d backend
```

---

## 测试账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | 123456 | BU 管理员（全权限） |
| pm_zhang | 123456 | 项目经理 |
| tech_li | 123456 | 技术经理 |
| product_wang | 123456 | 产品经理 |
| dev_zhao | 123456 | 开发 |
| test_sun | 123456 | 测试 |
| ui_zhou | 123456 | UI 设计 |

---

## 技术栈版本

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.2.4 |
| Vue | 3.4.x |
| MySQL | 8.0 |
| Redis | 7-alpine |
| Nginx | alpine |
| JDK | Eclipse Temurin 17 |
| Node.js | 20-alpine |
