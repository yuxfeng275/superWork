# P0 操作者身份与权限边界

## Goal

所有创建型审计字段必须来自认证请求的 JWT `userId`，禁止客户端通过 JSON body 伪造确认人、任务创建人、事项创建人、附件上传人、工时人员或设计人。

## Scope implemented

- 任务、事项、附件、普通工时、需求确认、设计工时控制器从 `@RequestAttribute("userId")` 传入 actor。
- 服务层在写入前校验 actor 非空并覆盖 DTO 中的 legacy actor 字段。
- 删除直接信任 actor 的无认证服务重载，避免内部调用绕过身份契约。
- 保留 DTO 字段仅用于兼容已有客户端 payload，不读取其值作为审计身份。

## Acceptance

- [x] Java 17 编译通过。
- [x] 后端 153 tests 通过。
- [x] 任务与需求确认回归测试证明 body actor 被忽略。
- [ ] 补充所有直接 ID 读写的项目级数据权限矩阵（后续安全基础任务）。
- [ ] 生产发布并验证恶意 actor payload 不改变审计字段。
