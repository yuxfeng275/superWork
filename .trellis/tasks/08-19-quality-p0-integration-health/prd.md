# P0 云效调度恢复与业务健康

## Goal

消除生产环境因云效加密主密钥缺失造成的持续调度异常，使驾驶舱/云效状态读取可用，并提供安全、可操作的恢复路径。

## Requirements

- 不生成伪造旧密钥，不删除或输出现有密文。
- 读取不可解密令牌时返回 `tokenSource=UNREADABLE`、`lastTestStatus=CONFIG_ERROR`，集成状态为未配置。
- 所有云效调度在配置不可用时安全跳过，不抛未捕获异常。
- 页面明确提示必须重新录入 Token；新 Token 使用新的 32-byte Base64 服务端根密钥加密。

## Acceptance Criteria

- [x] 缺失/错误密钥的服务层测试通过。
- [x] 后端全量测试和前端构建通过。
- [ ] 生产 `/api/bu-dashboard` 与 `/api/yunxiao/status` 返回 200。
- [ ] 新部署后至少 15 分钟无对应 scheduled task ERROR。
- [ ] 页面展示恢复提示，重新录入后可恢复连接测试。
