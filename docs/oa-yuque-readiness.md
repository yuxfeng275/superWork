# OA（致远）与语雀连通性确认（2026-09-17）

> 目的：说明这两个连接器「差什么」，以及需要谁做什么。
> 证据均来自服务器 241（出口 IP **59.46.222.98**）的实测探测 + 应用侧连接器测试。

## 一、OA（致远）——**不是登录问题**：只有 REST 接口被拦，网页登录通道是通的

### 实测（2026-09-17）

| 探测 | 结果 |
|---|---|
| `POST /seeyon/rest/token` | **HTTP 401 + HTML**（致远安全策略拦截页：「您没有权限访问此资源…」，请求未到达应用层） |
| `GET /seeyon/main.do`（网页登录页） | **HTTP 200** |
| `POST /seeyon/main.do?method=login`（表单登录，假账号） | **HTTP 302 + `loginerror: 1` + JSESSIONID**——说明表单登录链路可达，凭据被正常校验 |
| OA 组织同步历史 | 从未成功（`oa_org_department`/`oa_org_member` 均为 0 行） |

### 结论

**不是账号未登录导致**：同一个 401 用真/假账号都会发生；网页端登录通道（`main.do`）畅通。
真正被拦的只有 `/seeyon/rest/**` —— 致远的 **REST 接口安全策略（IP 白名单 / REST 服务未开通）**。
因此分两档：

1. **REST 通道（组织/待办/流程）**：把 `59.46.222.98` 加入致远 **REST 接口访问白名单**（致远 A8/V5：
   系统管理 → 集成配置 → REST 接口，需开通许可）。放行后无需改代码：连接器点「启用」→ 测试连接通过 →
   `oa-org` 定时同步（每日 06:20）与 OA 待办/已办查询即刻生效。
2. **vReport 合同采集（不走 REST，网页会话即可）**：网页表单登录（`main.do`）已验证可达，
   因此 `oa-contract` 理论上**不依赖 REST 白名单**——只要账号能登录网页端，配上
   连接器管理 → OA（致远）→ 合同导出地址（浏览器抓包得到的完整导出 URL，含查询参数），
   并在数据集成中心启用 `oa-contract` 任务即可；建议先用真实账号做一次表单登录验证。
3. 确认连接器里配置的服务账号具备：组织架构（部门/人员/岗位/职级）读取、协同事项（待办/已办/流程/表单）读取权限。

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
