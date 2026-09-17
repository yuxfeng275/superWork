# 部署文档 — 电商BU内部管理系统

> 更新时间：2026-09-17

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

241 生产（`docker/docker-compose.241.yml`）通过 Nginx 同时托管两套前端，共用同一后端：

```
浏览器
  │
  ▼
Nginx
  ├── :18080 /          → frontend-pro :80   （Ant Design Pro，当前默认入口）
  ├── :18080 /api/      → backend :8081
  ├── :18088 /          → frontend :80       （旧 Vue，兜底）
  ├── :18088 /api/      → backend :8081
  └── :18081            → backend :8081      （直连）

Backend :8081
  ├── MySQL  :3306
  ├── Redis  :6379
  └── MinIO  :9000
```

本地开发仍可用 `docker/docker-compose.yml`（Nginx :8000 / 旧前端 :8080 / 后端 :8081）。

### 241 容器列表

| 容器名 | 镜像 | 端口映射 | 说明 |
|--------|------|---------|------|
| superwork-bu-nginx | nginx:alpine | 18080:80、18088:8088 | 统一入口：新前端 :18080，旧前端 :18088 |
| superwork-bu-backend | 本地构建 | 18081:8081 | Spring Boot API |
| superwork-bu-frontend-pro | 本地构建 | 内部 80 | Ant Design Pro 前端 |
| superwork-bu-frontend | 本地构建 | 内部 80 | 旧 Vue 前端（兜底） |
| superwork-bu-mysql | mysql:8.0 | 127.0.0.1:13306:3306 | 数据库 |
| superwork-bu-redis | redis:7-alpine | 内部 6379 | 缓存 |
| superwork-bu-minio | minio/minio | 127.0.0.1:19000-19001 | 对象存储 |

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

### 更新部署（241）

先合入 `master`，再从 master 构建产物。frontend-pro 只发 dist，不要带上旧 Vue / 后端。

```bash
cd docker

# 仅更新新前端（常用）
docker compose -f docker-compose.241.yml up -d --build frontend-pro
docker restart superwork-bu-nginx   # 必须：upstream IP 在 nginx 启动时解析一次

# 仅更新旧前端兜底
docker compose -f docker-compose.241.yml up -d --build frontend
docker restart superwork-bu-nginx

# 仅更新后端
docker compose -f docker-compose.241.yml up -d --build backend
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

### 241 生产

| 服务 | 地址 | 说明 |
|------|------|------|
| **新前端（推荐）** | http://192.168.1.241:18080 | frontend-pro，Nginx 同时代理 `/api` |
| 旧前端（兜底） | http://192.168.1.241:18088 | 旧 Vue 工程 |
| 后端 API | http://192.168.1.241:18081 | Spring Boot 直连 |
| API 文档 | http://192.168.1.241:18081/doc.html | Knife4j |

> 家中网络也可走 `http://100.85.67.82:18080`。旧入口 `:18084` 已废弃，请改用 `:18080` / `:18088`。

### 本地开发

| 服务 | 地址 | 说明 |
|------|------|------|
| **前端应用（推荐）** | http://localhost:8000 | 通过 Nginx 访问，含 API 代理 |
| 前端直连 | http://localhost:8080 | 前端容器直连 |
| 后端 API | http://localhost:8081 | Spring Boot 直连（本地进程） |
| **API 文档** | http://localhost:8081/doc.html | Knife4j 接口文档 |
| MinIO 控制台 | http://localhost:9001 | 对象存储管理 |

> 本地推荐使用 **http://localhost:8000** 访问，通过 Nginx 统一代理前后端。

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
