# 电商BU内部管理系统设计文档

## 1. 项目背景与目标

**项目背景：**
电商业务BU负责人需要一套内部管理系统，用于管理BU内部的需求和事项。BU下有3条业务线：

- 全渠道云鹿定制
- 全渠道云鹿SAAS
- 会员通

每条业务线下有多个项目（如皇家项目下包含PMS、RC新体系、数据看板等子项目）。

**核心目标：**

1. 管理需求全生命周期：从评估、设计、开发、测试到上线交付
2. 管理临时事项：Bug修复、运维、小优化等
3. 工时管理：预估工时vs实际工时对比分析
4. 数据统计：人效分析、业务线健康度、需求全景、进度对比

## 2. 系统架构设计

### 2.1 整体架构

- 前端：Vue 3 + Element Plus + Vite
- 后端：Spring Boot 3.x + MyBatis Plus + Spring Security
- 数据库：MySQL 8.0
- 缓存：Redis 7.x
- 文件存储：MinIO
- 部署：内网部署，Nginx反向代理

### 2.2 核心模块

**前端模块：**

- 用户认证模块
- 需求管理模块
- 事项管理模块
- 任务管理模块
- 项目管理模块
- 数据看板模块
- 系统管理模块

**后端模块：**

- 认证授权模块（JWT）
- 需求管理模块（状态流转）
- 工作流引擎模块（可配置状态机）
- 统计分析模块
- 文件管理模块
- 系统管理模块

## 3. 核心数据模型

### 3.1 组织架构

**business_line（业务线表）**

- id, name（业务线名称）, description, status, created_at

**project（项目表）**

- id, business_line_id（所属业务线）
- parent_id（父项目ID，NULL表示主项目）
- level（层级：1=主项目，2=子项目）
- name（项目名称）, full_path（完整路径，如"皇家/PMS"）
- code（项目编码）, manager_id（项目经理ID）
- status, created_at

**user（用户表）**

- id, username, password, real_name
- role（角色：BU负责人/项目经理/产品经理/技术经理/前端研发/后端研发/测试/UI设计）
- email, phone, status, created_at

**project_member（项目成员表）**

- id, project_id, user_id
- role（在项目中的角色）
- joined_at

**customer_contact（客户联系人表）**

- id, project_id（所属项目）
- name（联系人姓名：Ember/Molly/张三等）
- company（公司名称）, position（职位）
- phone, email, is_active
- created_at

### 3.2 需求管理

**requirement（需求表）**

- id, req_no（需求编号，自动生成）
- title, description
- type（类型：项目需求/产品需求）
- business_line_id（所属业务线）
- project_id（所属项目，产品需求可为空）
- customer_contact_id（客户联系人ID，项目需求必填）
- status（状态：待评估/评估中/已拒绝/待设计/设计中/待确认/开发中/测试中/待上线/已上线/已交付/已验收）
- priority（优先级：高/中/低）
- source（来源：客户/商务/内部）
- expected_online_date（客户期望上线时间）
- estimated_online_date（评估后的预估上线时间）
- actual_online_date（实际上线时间）
- creator_id, created_at, updated_at

**requirement_evaluation（需求评估表）**

- id, requirement_id
- is_feasible（技术可行性：是/否）
- feasibility_desc（可行性说明）
- estimated_workload（预估工作量，人天）
- estimated_cost（预估报价，元，项目需求必填）
- work_breakdown（工作内容拆解，JSON）
- suggest_product（是否建议转产品需求）
- evaluator_id（评估人）, evaluated_at
- decision（BU决策：通过/拒绝/转产品需求）
- decision_by, decision_at, decision_reason

**requirement_design（需求设计汇总表）**

- id, requirement_id
- prototype_status（原型设计状态：未开始/进行中/已完成）
- ui_status（UI设计状态）
- tech_solution_status（技术方案状态）
- all_completed_at（全部完成时间）

**design_work_log（设计工作记录表）**

- id, requirement_id
- work_type（工作类型：原型设计/UI设计/技术方案设计）
- designer_id（设计人ID）
- estimated_hours（预估工时）
- actual_hours（实际工时）
- result_url（成果物链接或文件ID）
- work_content（工作内容描述）
- status（状态：进行中/已完成）
- started_at, completed_at

**requirement_confirmation（需求确认表）**

- id, requirement_id
- confirmation_type（确认类型：客户确认/内部确认）
- confirmed_by, confirmed_at
- confirmation_notes（确认备注）

**requirement_delivery（需求交付表）**

- id, requirement_id
- delivered_at（交付时间）
- accepted_at（验收时间）
- delivery_notes, acceptance_notes

### 3.3 任务与工时

**task（任务表）**

- id, requirement_id（关联需求）
- title, description
- task_type（任务类型：前端开发/后端开发/测试/UI设计）
- assignee_id（分配给谁）
- estimated_hours（预估工时）
- actual_hours（实际工时）
- status（状态：待开始/进行中/已完成/已测试）
- start_date, end_date（排期）
- created_by, created_at

**work_log（工时记录表）**

- id, task_id, requirement_id（可选）
- user_id, work_date
- hours（工时，小时）
- description（工作内容描述）
- created_at

### 3.4 事项管理

**issue（事项表）**

- id, issue_no（事项编号）
- title, description
- type（类型：Bug修复/运维/临时需求/优化）
- business_line_id, project_id（可选关联）
- requirement_id（可选关联需求）
- assignee_id, creator_id
- estimated_hours, actual_hours
- status（状态：待处理/处理中/已完成/已验证）
- priority, created_at, completed_at
- **说明**：Bug类事项完成后需要创建人验证，其他类型直接标记完成

### 3.5 工作流配置

**workflow_config（工作流配置表）**

- id, requirement_type（需求类型：项目需求/产品需求）
- from_status（源状态）
- to_status（目标状态）
- allowed_roles（允许的角色，JSON数组）
- condition（流转条件，JSON）
- is_active（是否启用）
- sort_order（排序）
- created_at, updated_at

**附件表（attachment）**

- id, file_name, file_path（MinIO路径）
- file_size, file_type
- related_type（关联类型：需求/任务/事项）
- related_id（关联ID）
- uploaded_by, uploaded_at

## 4. 核心业务流程

### 4.1 需求生命周期

**项目需求：** 创建 → 待评估 → 评估中 → [已拒绝|待设计] → 设计中 → 待确认(客户) → 开发中 → 测试中 → 待上线 → 已上线 → 已交付 → 已验收

**产品需求：** 创建 → 待评估 → 评估中 → [已拒绝|待设计] → 设计中 → 待确认(内部) → 开发中 → 测试中 → 待上线 → 已上线

**需求转换：** 项目需求可在评估阶段转为产品需求（技术经理/产品经理建议，BU负责人审批）

**默认状态流转权限：**
| 状态流转 | 允许角色 | 前置条件 |
|---------|---------|---------|
| 待评估 → 评估中 | 技术经理、产品经理 | 无 |
| 评估中 → 已拒绝 | BU负责人 | 评估已完成 |
| 评估中 → 待设计 | BU负责人 | 评估已完成且决策通过 |
| 评估中 → 转产品需求 | BU负责人 | 评估建议转换 |
| 待设计 → 设计中 | 产品经理、UI设计、技术经理 | 无 |
| 设计中 → 待确认 | 系统自动 | 所有设计工作已完成 |
| 待确认 → 开发中 | 项目经理（项目需求）、产品经理（产品需求） | 确认已完成 |
| 开发中 → 测试中 | 技术经理 | 所有开发任务已完成 |
| 测试中 → 待上线 | 测试人员 | 测试通过 |
| 待上线 → 已上线 | 项目经理 | 无 |
| 已上线 → 已交付 | 项目经理 | 仅项目需求 |
| 已交付 → 已验收 | 项目经理 | 仅项目需求 |

**说明：** 以上为默认配置，可在系统管理中修改

### 4.2 评估流程

1. 技术团队评估可行性、预估工时、报价、上线时间
2. BU负责人决策：通过/拒绝/转产品需求

### 4.3 设计流程

- 原型设计（产品经理）
- UI设计（UI设计师）
- 技术方案设计（技术经理）
- 各自独立填写预估工时和实际工时

**设计完成条件：**

- 需求可能需要1-3种设计工作（原型/UI/技术方案）
- 项目经理/产品经理在"待设计"阶段指定需要哪些设计工作
- 所有指定的设计工作都标记为"已完成"后，系统自动将需求流转到"待确认"
- 如果某个需求只需要技术方案设计，则技术方案完成即可流转

### 4.4 开发流程

- 技术经理拆分任务并分配
- 研发人员执行任务并填写工时
- 所有任务完成后流转到测试

### 4.5 测试与上线

- 测试人员执行测试，发现Bug创建事项
- 测试通过后，项目经理决定上线时机
- 项目需求需要记录交付和验收节点

## 5. 权限控制设计

### 5.1 权限模型

RBAC + 数据权限（按项目隔离）

**角色：**

- BU负责人：全局数据访问
- 项目经理：按项目隔离
- 技术经理：挂在项目经理下
- 产品经理：按业务线
- 研发/测试/UI设计：按任务分配

**技术经理权限说明：**

- 技术经理通过 `project_member` 表关联到具体项目
- 一个技术经理可以参与多个项目（在不同项目中都有成员记录）
- 技术经理可以看到自己参与的所有项目的需求和任务
- 技术经理不能看到其他项目的数据（除非也是该项目成员）

### 5.2 数据权限

- 前端：路由守卫 + 按钮级权限指令
- 后端：注解式权限验证 + MyBatis Plus数据权限拦截器

## 6. 数据统计与看板

### 6.1 BU负责人看板

- 需求全景看板（看板视图）
- 业务线健康度对比
- 人效分析（工作饱和度、工时偏差）
- 周度进度对比（上周vs本周）
- 团队工作量透视（人员饱和度热力图）

### 6.2 项目经理看板

- 我的项目概览
- 需求进度跟踪
- 团队工作量
- 周度对比

### 6.3 技术经理看板

- 技术任务概览
- 研发资源分配
- 工时偏差分析

### 6.4 研发人员看板

- 我的工作台
- 任务列表
- 工时填报

## 7. 技术实现要点

### 7.1 工作流状态机

- 可配置的状态流转规则
- 角色权限验证
- 前置条件检查

### 7.2 工时统计

- 预估工时 vs 实际工时双轨记录
- 人员饱和度计算：实际工时 / (5天 \* 8小时)
- 工时偏差分析

### 7.3 性能优化

- Redis缓存（权限、配置、统计数据）
- 分页查询
- 异步任务（大数据导出）
- 前端懒加载

### 7.4 部署方案

- 内网部署
- 应用服务器：4核8G
- 数据库服务器：8核16G
- 每日数据库备份

## 8. 关键设计决策

1. **需求分类**：项目需求（外部客户）vs 产品需求（内部规划），可在评估阶段转换
2. **组织架构**：业务线 → 项目（支持子项目）→ 团队
3. **工时管理**：预估+实际双轨，用于偏差分析
4. **权限隔离**：项目经理按项目隔离，技术经理跟随项目
5. **工作流可配置**：状态流转规则可在系统管理中配置
6. **设计工作拆分**：原型/UI/技术方案独立记录工时
7. **客户信息结构化**：独立客户联系人表，可复用
8. **时间跟踪**：客户期望时间、预估时间、实际时间三个维度
9. **事项轻量化**：直接分配处理，不走完整评估流程
10. **周度对比**：上周vs本周的进度变化和人员工作量对比

## 9. 后续扩展

第一期暂时独立运行，后续可考虑：

- 集成企业微信/钉钉（消息通知）
- 集成财务系统（报价、回款）
- 集成CRM（客户信息）

## 10. 核心API接口设计

### 10.1 需求管理接口

- POST /api/requirement/create - 创建需求
- GET /api/requirement/list - 需求列表（支持分页、筛选）
- GET /api/requirement/{id} - 需求详情
- PUT /api/requirement/{id}/status - 更新需求状态
- POST /api/requirement/{id}/evaluate - 提交评估
- POST /api/requirement/{id}/decision - BU决策
- POST /api/requirement/{id}/convert - 转换为产品需求

### 10.2 设计工作接口

- POST /api/design/create - 创建设计工作记录
- PUT /api/design/{id} - 更新设计工作（工时、成果物）
- GET /api/design/requirement/{reqId} - 获取需求的所有设计工作

### 10.3 任务管理接口

- POST /api/task/create - 创建任务
- GET /api/task/list - 任务列表
- PUT /api/task/{id}/status - 更新任务状态
- POST /api/task/{id}/worklog - 填写工时

### 10.4 事项管理接口

- POST /api/issue/create - 创建事项
- GET /api/issue/list - 事项列表
- PUT /api/issue/{id}/status - 更新事项状态
- POST /api/issue/{id}/verify - 验证事项（Bug类）

### 10.5 统计分析接口

- GET /api/dashboard/bu - BU负责人看板数据
- GET /api/dashboard/pm/{projectId} - 项目经理看板
- GET /api/dashboard/tech/{userId} - 技术经理看板
- GET /api/statistics/workload - 人员工作量统计
- GET /api/statistics/week-compare - 周度对比数据
- GET /api/statistics/saturation - 人员饱和度分析

### 10.6 系统管理接口

- GET /api/workflow/config - 获取工作流配置
- PUT /api/workflow/config - 更新工作流配置
- GET /api/project/tree - 项目树（含子项目）
- POST /api/customer/contact - 创建客户联系人

---

**文档版本：** v1.1  
**创建日期：** 2026-04-01  
**最后更新：** 2026-04-02  
**更新内容：** 补充详细数据模型字段、状态流转权限表、设计完成条件、技术经理权限说明、核心API接口设计
