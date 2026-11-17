# 企微 HTTP 回调 smoke（2026-11-18）

在 241 上用**一次性测试配置**验证 `GET/POST /api/wecom/callback`，不产生真实企微消息；
测试 token / AESKey / CorpID 由脚本临时生成，验证后从 `.env` 移除并重启，恢复 503 降级。

| 场景 | 结果 |
|---|---|
| 配置后 GET 验证 URL（签名 + 解密 echostr） | `200`，返回 `echo-ok` |
| 配置后 POST 文本消息（签名 + 解密 + 入库 + 加密被动回复） | `200`，响应含 `<Encrypt>`（667 字符） |
| 移除配置后再次 GET | `503 WECOM_CALLBACK_NOT_CONFIGURED` |

- 请求经 nginx `:18080` 进入，路径与生产一致；
- 字符编码按 UTF-8 字节长度写入 msgLen（首轮 smoke 用字符数导致 400，已修正脚本后通过）；
- 单测 `WeComCallbackCryptoTest` / `WeComCallbackControllerTest` 覆盖签名不匹配、receiveId 不匹配、未配置降级与消息入站。
