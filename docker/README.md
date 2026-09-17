# Docker 环境配置

## 服务列表

- **MySQL 8.0** - 数据库服务
  - 端口: 3306
  - 用户: bu_admin
  - 密码: bu_admin123
  - 数据库: bu_management

- **Redis 7** - 缓存服务
  - 端口: 6379

- **MinIO** - 对象存储服务
  - API 端口: 9000
  - Console 端口: 9001
  - 用户: minioadmin
  - 密码: minioadmin123

- **Nginx** - 反向代理
  - 端口: 80

- **Backend** - Spring Boot 后端
  - 端口: 8081

- **Frontend** - Vue 3 前端
  - 端口: 8080

## 快速启动

### 1. 启动所有服务

```bash
cd docker
docker-compose up -d
```

### 2. 查看服务状态

```bash
docker-compose ps
```

### 3. 查看日志

```bash
# 查看所有服务日志
docker-compose logs -f

# 查看特定服务日志
docker-compose logs -f mysql
docker-compose logs -f backend
```

### 4. 停止服务

```bash
docker-compose down
```

### 5. 重启服务

```bash
docker-compose restart
```

## 仅启动基础设施（开发环境）

如果只需要启动 MySQL、Redis、MinIO，不启动应用：

```bash
docker-compose up -d mysql redis minio
```

## 数据持久化

数据存储在以下目录：
- `./mysql/data` - MySQL 数据
- `./redis/data` - Redis 数据
- `./minio/data` - MinIO 数据

## 访问地址

- 前端: http://localhost
- 后端 API: http://localhost/api
- API 文档: http://localhost/doc.html
- MinIO Console: http://localhost:9001

## 注意事项

1. 首次启动需要等待 MySQL 初始化完成（约 30 秒）
2. 确保端口 80、3306、6379、8080、8081、9000、9001 未被占用
3. 生产环境请修改默认密码
4. 数据库会自动执行 Flyway 迁移脚本
