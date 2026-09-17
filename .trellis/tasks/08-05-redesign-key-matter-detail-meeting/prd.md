# 优化大事儿详情与周会视觉布局

## Goal

重构“大事儿详情”和“周会模式”的信息层级，让管理者能够快速读懂事项结论、当前健康度、风险、下一步行动和需要决策的内容，同时保持现有数据与操作流程不变。

## Requirements

* 详情抽屉从顺序堆叠改为“事项总览 + 本周简报 + 关键信息侧栏 + 周历史时间线”。
* 详情首屏明确呈现状态、优先级、总进度、周环比、负责人、项目和完成期限。
* 最新周进展区分主进展、风险、下一步和需协调事项，不再使用等权三宫格。
* 周历史按时间线展示每周状态、进度、环比和进展摘要，并保留编辑、删除操作。
* 周会事项采用“主进展 + 风险/决策侧栏 + 下一步行动条”结构，适合会议投屏和快速扫描。
* 保留按负责人/项目汇总、周切换、缺失周报操作入口、里程碑和台账模式。
* 桌面和移动端均不得横向溢出，交互元素保持可见焦点状态。

## Acceptance Criteria

* [x] 详情首屏不滚动即可看到标题、状态、进度、负责人、项目、周期和最新周进展入口。
* [x] 最新周进展按信息重要性排布，风险和需决策内容具有语义强调。
* [x] 历史周进展形成连续时间线，并显示相邻周进度变化。
* [x] 周会卡片能按“进展—风险/决策—下一步”顺序阅读。
* [x] 缺失周报仍可直接点击补充。
* [x] 既有 5 个端到端场景通过，并新增详情/周会布局断言。
* [x] 前端构建、桌面/移动端截图和部署后 smoke test 通过。

## Design Direction

* Aesthetic: 紧凑的管理简报 / 运营编辑台。
* Palette: 复用现有蓝灰色体系，红、橙、绿只表达风险、警告和完成状态。
* Signature move: 周会使用“主进展 + 风险决策侧栏 + 下一步行动条”，详情使用“周简报 + 事项事实侧栏”。
* Density: 桌面紧凑但不拥挤，移动端按阅读顺序自然折叠。

## Technical Approach

* 仅修改 `frontend/src/views/KeyMattersView.vue` 的模板和 scoped CSS，复用已有数据和事件函数。
* 增加纯前端周历史环比辅助函数，不改变 API、数据库或后端服务。
* 扩展 `frontend/tests/key-matters.spec.ts` 的语义布局与移动端回归断言。
* 更新 `.trellis/spec/frontend/key-matter-management-ui.md`。

## Out of Scope

* 修改周进展字段、接口或数据库。
* 自动生成周报内容。
* 重做台账模式、里程碑模式或全局导航。

## Definition of Done

* Build and feature E2E pass.
* Desktop and mobile browser screenshots have no overlap or overflow.
* Production frontend is deployed and verified at the project URL.
