# OA（致远）与语雀连通性确认（2026-09-17）

> 目的：说明这两个连接器「差什么」，以及需要谁做什么。
> 证据均来自服务器 241（出口 IP **59.46.222.98**）的实测探测 + 应用侧连接器测试。

## 一、OA（致远）——差「网络放行」，其余已就绪

### 实测

| 探测 | 结果 |
|---|---|
| `POST https://oa.lucidata.cn/seeyon/rest/token` | **HTTP 401 + HTML**：「您没有权限访问此资源。详细原因请联系管理员查看后台日志。」（前置网关拦截页，请求未到达 OA REST 服务） |
| `GET https://oa.lucidata.cn/` | HTTP 302（站点本身可达） |
| 连接器凭据 | 已配置（账号/密码在连接器里）、连接器状态「已停用」（网关不通期间保持停用，避免每日同步任务报错） |
| OA 组织同步历史 | 从未成功（`oa_org_department`/`oa_org_member` 均为 0 行） |

### 结论

代码与配置已就绪，**唯一缺的是 OA 侧对这个 IP 的放行**（致远的前置网关/REST 白名单）。
放行后无需改代码：连接器里点「启用」→ 测试连接通过 → `oa-org` 定时同步（每日 06:20）与 OA 待办/已办查询即刻生效。

### 需要在 OA 侧完成

1. **把 `59.46.222.98` 加入致远 REST 接口访问白名单**（这是唯一硬阻塞）。
2. 确认 OA 已开通 REST 服务（致远 A8/V5：系统管理 → 集成配置 → REST 接口，需有许可）。
3. 确认连接器里配置的服务账号具备：组织架构（部门/人员/岗位/职级）读取、协同事项（待办/已办/流程/表单）读取权限。
4. 如果要用「OA 销售合同（vReport）同步」，还需要：浏览器抓包得到**销售合同查询导出接口的完整 URL（含查询参数）**，填入 连接器管理 → OA（致远）→ 合同导出地址；随后在数据集成中心启用 `oa-contract` 任务。

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

## 二、语雀——**功能已经通了**（自动走 REST v2），MCP 是可选的加速通道

### 实测

| 探测 | 结果 |
|---|---|
| `POST https://mcp.yuque.com/mcp`（initialize，无 token） | HTTP **403** |
| 同上（带任意 Bearer） | HTTP **403** |
| 同上（路径 `www.yuque.com/mcp`） | HTTP **403** |
| `GET https://www.yuque.com/api/v2/user`（REST v2） | HTTP 401（无 token）→ 端点可达 |
| 应用连接器测试（带真实 token） | 「Token 有效（REST 已验证），但 MCP 网关拒绝(403)」→ 查询/阅读/周报发布自动降级 REST v2 |

### 结论

- **语雀不是「差什么」而是「已经可用」**：MCP 网关对我们这个出口 IP 一律 403（连无 token 也 403，属网关级限制，不是 Token 权限问题），
  应用已实现自动降级：`search_yuque_docs`、`read_yuque_doc`、周会纪要发布都走语雀 **REST v2**（同一 Token）。
- MCP 通道要走通，需要语雀侧（或企业版管理员）在该账号/组织上**开通 MCP 服务并把服务器出口 IP 放行**（语雀「设置 → MCP 服务」）。
  开通后不需要改配置：连接器测试会从 403 变为通过，且流量自动切回 MCP。

### 需要在语雀侧完成（可选）

1. 组织管理员在语雀「设置 → MCP 服务」为该 Token 所属账号开通 MCP；
2. 确认语雀网关未限制服务器出口 IP（`59.46.222.98`）；
3. 若 MCP 仍 403，保持现状即可——REST v2 通道功能等价（延迟略高、单文档截断 12k 字符）。
