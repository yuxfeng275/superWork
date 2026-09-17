# OA（致远）与语雀连通性确认（2026-09-17）

> 目的：说明这两个连接器「差什么」，以及需要谁做什么。
> 证据均来自服务器 241（出口 IP **59.46.222.98**）的实测探测 + 应用侧连接器测试。

## 一、OA（致远）——**不是登录问题**：只有 REST 接口被拦，网页登录通道是通的

### 实测（2026-09-17，A8 **V9.0**，登录页核实版本）

| 探测 | 结果 |
|---|---|
| `POST /seeyon/rest/token`（服务器出口 59.46.222.98 / 本机出口 23.237.196.226，两个出口真/假账号） | **HTTP 401 + HTML**（致远权限拦截页「您没有权限访问此资源…」；两个公网出口结果一致 → 不是单 IP 白名单问题，是 REST 通道整体未放行） |
| `GET /seeyon/main.do`（网页登录页） | **HTTP 200** |
| `POST /seeyon/main.do?method=login`（表单登录，假账号） | **HTTP 302 + `loginerror: 1` + JSESSIONID**——表单登录链路可达，凭据被正常校验 |
| `GET /seeyon/rest/`、`/seeyon/rest/api/orgMember` | **HTTP 401**（而非 404 → REST 路由存在，被安全策略拦） |
| 不存在路径 | HTTP 404 |
| OA 组织同步历史 | 从未成功（`oa_org_department`/`oa_org_member` 均为 0 行） |
### 结论

**不是"只欠一个白名单"**：两个公网出口、真/假账号都是同一个 401 → 致远侧把 `/seeyon/rest/**`
整体拦住了（REST 路由存在、安全策略不放行），与登录状态无关。所以「能登录网页端就能调接口」不成立：
致远里**网页通道与 REST 接口是两套访问控制**。

### 需要在 OA 侧完成（给 OA 管理员的清单，A8 V9.0）

1. **先确认 REST 接口服务已开通**（A8 V9：系统管理 → 接口管理/集成配置里的 REST（OpenAPI）服务；
   是否开通、是否有许可——未开通则 /seeyon/rest/** 全 401，正如此刻）。
2. **若已开通但仍有来源限制**：在 REST 的访问策略/白名单里放行服务器出口 **59.46.222.98**。
3. **建服务账号**：组织架构（部门/人员/岗位/职级）+ 协同事项（待办/已办/流程/表单）只读权限。
4. **自测定位**（在 OA 服务器本机跑，能直接区分"没开通" vs "白名单"）：
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1/seeyon/rest/token \
     -H "Content-Type: application/json" -d '{"userName":"<服务账号>","password":"<密码>"}'
   ```
   - localhost 200 → 外网/安全策略拦：放行 59.46.222.98 即可。
   - localhost 也 401 → REST 未开通或账号密码错：先开 REST 服务、再建账号。
   - 同时查看 OA 后台日志（401 页面提示"详细原因请联系管理员查看后台日志"）。
5. **不需要致远开放平台**：开放平台是三方应用 OAuth 集成通道，本机直连 REST 不依赖它、
   不需要注册应用；只有当管理员确认该部署只提供开放平台 OAuth 时，才需要走那条路（届时再谈 appId/secret）。
6. **过渡方案（不依赖 REST）**：`oa-contract` vReport 合同采集走网页表单登录 + 会话 Cookie（已验证可达）——
   只需真实账号 + 「合同导出地址」（抓包），可先行启用；组织/待办查询仍需等 REST 放行。

### 系统侧会用到的 OA 接口（供 OA 管理员核对放行范围）

```
POST /seeyon/rest/token                    认证
GET  /seeyon/rest/api/orgDepartment        部门
GET  /seeyon/rest/api/orgMember            人员（可选 ?departmentId=）
GET  /seeyon/rest/api/orgPost              岗位
GET  /seeyon/rest/api/orgLevel             职级
GET  /seeyon/rest/api/affair/pending       待办
GET  /seeyon/rest/api/affair/done          已办
GET  /seeyon/rest/api/flow/{flowId}        流程详情
GET  /seeyon/rest/api/form/{formId}        表单详情
GET  <vReport 导出地址>                     销售合同明细（抓包取得，连接器里配置）
```

### RPA（网页通道）三级方案——REST 不开通时的替代路线

> 网页通道与 REST 是两套访问控制：REST 被拦时，网页会话仍然可达（已实测）。
> 当前卡点：**连接器里存的 OA 账号连网页表单登录都失败（loginerror:1，账号密码错或已停用）**——
> 换有效账号后下列方案立即可用（连接器测试按钮现在会分别标注「REST」与「网页会话通道」的结果）。

| 级 | 方案 | 状态 | 覆盖 |
|---|---|---|---|
| T0 | 网页会话重放：form login + JSESSIONID + servlet 抓取 | **已实现**（vReport 合同采集，链路实测可达） | 销售合同 |
| T1 | 网页/移动端 JSON 接口重放：移动端 `/seeyon/mobile.do` 实测可达（200，不走 REST 白名单），抓包待办/组织接口后接入 SeeyonOaClient | 待抓包后实现（~1 天） | 待办/已办、组织人员 |
| T2 | 真浏览器 RPA（Playwright 登录 + 抓 DOM） | 兜底，仅 T0/T1 不通时做；实现藏在 DataCollector 后，REST 放行后一键换回 | 全部 |
## 二、语雀——**已经通了**（REST v2 与官方 server 同一条链路）；MCP 网关不需要放开

### 官方参考：[yuque/yuque-mcp-server](https://github.com/yuque/yuque-mcp-server)（2026-09-17 核实）

语雀官方开源的 MCP server 是**本地 stdio 进程**（`npx yuque-mcp`），它内部调用的就是语雀 **REST API**
（`{YUQUE_HOST}/api/v2` + `X-Auth-Token`，团队空间需传 `--host=https://your-space.yuque.com`）。
也就是说：**官方方案根本不走 `mcp.yuque.com` 网关**，而我们已实现的 REST v2 通道与官方 server 是同一条链路。

### 实测

| 探测 | 结果 |
|---|---|
| `POST https://mcp.yuque.com/mcp`（initialize，无 token） | HTTP **403** |
| 同上（带任意 Bearer） | HTTP **403** |
| `GET {站点}/api/v2/user`（REST v2，带真实 Token） | **HTTP 200**（Token 有效） |
| 应用连接器测试 | 连接成功（REST v2 通道；语雀 MCP 网关受限，自动走 REST v2） |

### 结论

- **语雀已经可用**：AI 搜索/阅读语雀文档、周会纪要发布都走语雀 REST v2（同一 Token），
  与官方 yuque-mcp-server 用的 API 完全一致；连接器测试按「REST v2 可用即通过」判定（不再把 MCP 网关 403 记为失败）。
- `mcp.yuque.com` 网关对我们出口 IP 一律 403（连无 token 也 403，属网关级限制），但**不依赖它**，无需处理。
- 如果将来想要官方 server 的完整 19 个工具（画板、小记等），可在单独容器跑
  `npx yuque-mcp --token=<token> --host=https://lucidata.yuque.com`（stdio MCP server），与本系统并存，零改造成本。
