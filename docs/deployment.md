# 部署文档 — 电商BU内部管理系统

> 更新时间：2026-09-17

---

## 目录

1. [环境要求](#环境要求)
2. [服务架构](#服务架构)
3. [快速部署](#快速部署)
4. [241 生产部署（superwork-bu）](#241-生产部署superwork-bu)
5. [访问地址](#访问地址)
6. [服务配置说明](#服务配置说明)
7. [会议转写 worker（asr-worker）](#会议转写-worker-asr-worker)
8. [常用运维命令](#常用运维命令)
9. [故障排查](#故障排查)
10. [测试账号](#测试账号)

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
  ├── :18080 /          → frontend-pro :80   （Ant Design Pro）
  ├── :18080 /api/      → backend :8081
  └── :18081            → backend :8081      （直连）

Backend :8081
  ├── MySQL  :3306
  ├── Redis  :6379
  ├── MinIO  :9000
  └── asr-worker :8790   ← 会议转写（docker compose 服务）
```

本地开发仍可用 `docker/docker-compose.yml`（Nginx :8000 / 前端 :8080 / 后端 :8081；frontend 服务即 frontend-pro，旧 Vue 已于 2026-09-25 下线）。

### 241 容器列表

| 容器名 | 镜像 | 端口映射 | 说明 |
|--------|------|---------|------|
| superwork-bu-nginx | nginx:alpine | 18080:80 | 统一入口：前端 :18080 |
| superwork-bu-backend | 本地构建 | 18081:8081 | Spring Boot API |
| superwork-bu-frontend-pro | 本地构建 | 内部 80 | Ant Design Pro 前端 |
| ~~superwork-bu-frontend~~ | — | — | 旧 Vue 前端，2026-09-25 已下线删除 |
| superwork-bu-mysql | mysql:8.0 | 127.0.0.1:13306:3306 | 数据库 |
| superwork-bu-redis | redis:7-alpine | 内部 6379 | 缓存 |
| superwork-bu-minio | minio/minio | 127.0.0.1:19000-19001 | 对象存储 |
| superwork-bu-asr-worker | 本地构建 | 不发布（bu-network 内网 :8790） | 会议转写 worker |

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

# 仅更新前端（常用）
docker compose -f docker-compose.241.yml up -d --build frontend-pro
docker restart superwork-bu-nginx   # 必须：upstream IP 在 nginx 启动时解析一次

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

## 241 生产部署（superwork-bu）

生产环境为 `192.168.1.241`（部署目录 `/home/openclaw/superwork-claude-sp/`，git 检出、镜像在 241 上构建），使用 `docker/docker-compose.241.yml`：compose 项目名 `superwork-bu`，容器名统一 `superwork-bu-*`，与上文本地 dev 拓扑（`bu-management-*`）相互独立。

### 服务与端口

| 服务名 | 容器名 | 宿主端口 | 说明 |
|--------|--------|---------|------|
| nginx | superwork-bu-nginx | 18080（前端入口） | 统一入口，`/api/` 反代 backend |
| backend | superwork-bu-backend | 18081 | Spring Boot API，会议音频落 `/data/meetings` |
| frontend-pro | superwork-bu-frontend-pro | 经 nginx 18080 | Ant Design Pro 前端（会议模块 `/meetings`）；旧 Vue 前端 2026-09-25 已下线 |
| ai-sidecar | superwork-bu-ai-sidecar | 8787 | AI 助手旁路服务 |
| asr-worker | superwork-bu-asr-worker | 不发布（仅 bu-network 内网） | 会议录音转写，见下节 |
| mysql | superwork-bu-mysql | 127.0.0.1:13306 | 数据库 |
| redis | superwork-bu-redis | 不发布 | 缓存 |
| minio | superwork-bu-minio | 127.0.0.1:19000 / 19001 | 对象存储 |

### 更新部署

前端镜像只复制宿主机预构建的 `dist/`（backend 为 jar 多阶段构建），所以**改了前端必须先在 241 宿主执行 `npm run build`，再 `up -d --build`**：

```bash
# 1. 在 241 上构建前端产物（frontend-pro 会议模块改动时同样要执行）
ssh 241 'cd /home/openclaw/superwork-claude-sp/frontend-pro && npm run build'

# 2. 重建并启动变更的服务（示例：后端 + 新前端 + 转写 worker）
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && \
  docker compose -f docker-compose.241.yml up -d --build backend frontend-pro asr-worker && \
  docker compose -f docker-compose.241.yml ps'
```

### 数据目录（241 宿主）

```
/home/openclaw/superwork-claude-sp/docker/
├── data/meetings/   ← 会议音频文件（backend 卷 ./data/meetings:/data/meetings）
└── asr-models/      ← 转写模型缓存（asr-worker 卷 ./asr-models:/models）
```

### 首次启用会议模块

1. 创建录音目录并授权给后端容器用户（uid 999；容器以 `spring` 用户运行，宿主目录由 Docker 以 root 创建时上传会报 500 `AccessDeniedException: /data/meetings/tmp`）：

   ```bash
   ssh 241 'mkdir -p /home/openclaw/superwork-claude-sp/docker/data/meetings && \
     docker run --rm -v /home/openclaw/superwork-claude-sp/docker/data/meetings:/d \
     nginx:alpine chown -R 999:999 /d'
   ```

2. 在 `docker/` 下（或部署环境变量）设置 `MEETING_ASR_TOKEN`，使 compose 的 `${MEETING_ASR_TOKEN:-}` 生效（为空则 worker 不校验 token）。
3. `docker compose -f docker-compose.241.yml up -d --build asr-worker backend frontend-pro`，`ps` 确认 asr-worker 为 `healthy`。
4. 管理端「系统配置」启用 `meeting` 组，`meeting.asr.base-url` 设为 `http://asr-worker:8790`，`meeting.asr.token` 与 `MEETING_ASR_TOKEN` 保持一致。

---

## 访问地址

### 241 生产

| 服务 | 地址 | 说明 |
|------|------|------|
| **前端应用** | http://192.168.1.241:18080 | frontend-pro，Nginx 同时代理 `/api` |
| 后端 API | http://192.168.1.241:18081 | Spring Boot 直连 |
| API 文档 | http://192.168.1.241:18081/doc.html | Knife4j |

> 家中网络也可走 `http://100.85.67.82:18080`。旧 Vue 前端与 `:18084` / `:18088` 入口已于 2026-09-25 下线。

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
├── minio/
│   └── data/     ← 对象存储文件
└── asr-models/   ← 会议转写模型缓存（首次启动自动下载）
```

---

## 会议转写 worker（asr-worker）

会议模块的录音转写由自托管 FunASR worker 完成（音频不出内网），HTTP 契约：`GET /healthz` → `{"ok":true}`；`POST /v1/transcriptions`（multipart 字段 `audio`，可选 `hotword`；worker 配置了 token 时校验请求头 `X-Meeting-Token`）。

### 构建 / 端口 / 卷

| 项 | 241 生产（`docker-compose.241.yml`） | 本地 dev（`docker-compose.yml`） |
|----|------------------------------------|----------------------------------|
| 构建上下文 | `../asr-worker`（`python:3.11-slim` + ffmpeg） | 同左 |
| 容器端口 | 8790 | 8790 |
| 宿主端口 | **不发布**，仅 `bu-network` 内网 | `8790:8790` |
| 模型卷 | `./asr-models:/models`（env `MODEL_DIR=/models`） | 同左 |
| 鉴权 | env `MEETING_TOKEN`（空 = 不校验） | 同左 |
| backend 接入地址 | `http://asr-worker:8790` | `http://localhost:8790` |

首次启动会把 SenseVoiceSmall 等模型下载到 `docker/asr-models/`（宿主机持久化，后续复用该卷）；下载与加载期间容器处于 `starting`。

```bash
# 241：只重建转写 worker
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && docker compose -f docker-compose.241.yml up -d --build asr-worker'

# 本地 dev：只启动转写 worker（宿主 backend 经 http://localhost:8790 访问）
cd docker && docker compose up -d asr-worker
```

> **慢链路加速（首次构建实测 18 分钟）**：241 到 `deb.debian.org` / `pypi.org` / `download.pytorch.org` 实测 4KB~90KB/s。
> Dockerfile 支持 `--build-arg`：`APT_MIRROR`（Debian 镜像主机名）、`PIP_INDEX_URL`、`PIP_EXTRA_INDEX_URL`、`PIP_FIND_LINKS`（镜像内 wheelhouse 路径）。
> 推荐做法：本机（国际链路）`pip download` 预取 CPU 轮子（torch+torchaudio ≈ 190MB，`--platform manylinux_2_28_x86_64 --python-version 3.11 --abi cp311`）→ rsync 到 241 的 `asr-worker/wheels/` → 用阿里云源构建：

```bash
docker compose -f docker-compose.241.yml build \
  --build-arg APT_MIRROR=mirrors.aliyun.com \
  --build-arg PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
  --build-arg PIP_EXTRA_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
  --build-arg PIP_FIND_LINKS=/wheels asr-worker
```

### 健康检查

镜像为 slim 无 curl，healthcheck 用 python urllib 探活：

```yaml
healthcheck:
  test: ["CMD", "python", "-c", "import urllib.request;urllib.request.urlopen('http://localhost:8790/healthz')"]
  interval: 30s
  timeout: 10s
  retries: 20
  start_period: 120s
```

`docker compose ps` 显示 `healthy` 表示服务已就绪（模型加载慢时先显示 `starting`）。

### 系统配置（管理端连接）

| 配置项 | 241 生产 | 本地 dev |
|--------|---------|---------|
| `meeting.enabled` | `true`（默认 false，需启用） | 同左 |
| `meeting.asr.base-url` | `http://asr-worker:8790` | `http://localhost:8790` |
| `meeting.asr.token` | 与 compose 的 `MEETING_ASR_TOKEN` 一致（都为空则不校验） | 同左 |
| `meeting.audio.max-size-mb` | 按需（默认 100） | 同左 |
| `meeting.asr.timeout-seconds` | 按需（默认 3600） | 同左 |

> 241 不发布 asr-worker 宿主端口，只能在 `bu-network` 内访问，因此 base-url 必须用容器名 `asr-worker`，不能用 `localhost`；本地 dev 相反——backend 跑在宿主机上，只能走 `http://localhost:8790`。

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
