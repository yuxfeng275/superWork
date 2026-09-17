# 大事儿管理周视图与里程碑

## Goal

在现有“大事儿管理”上增强日期选择、周会汇总、周环比和里程碑展示，让管理者可以按负责人开周会，并直接判断事项相对上一周的进度变化与关键交付节点。

## What I already know

* 页面位于 `frontend/src/views/KeyMattersView.vue`，现有台账模式和周会模式。
* 后端 `GET /api/key-matters/meeting` 已返回指定周进展以及完整历史周进展，足以计算上周对比，无需新增接口。
* 每个事项已有负责人、开始日期、计划完成日期、状态和进度。
* Element Plus 当前未配置中文 locale，日期选择器会使用默认语言。

## Assumptions

* 周会支持“按负责人”和“按项目”两种汇总口径，默认按负责人。
* “里程碑视图”先将每个事项的计划完成日期作为一个里程碑节点，不新增多级里程碑维护能力。
* 周对比以所选周与上一自然周的周进展为准；上一周未填时明确显示“上周无数据”，不拿当前事项总进度冒充历史进度。

## Requirements

* Element Plus 日期选择器使用中文语言包，日期显示使用中文格式。
* 周会模式可在按负责人、按项目之间切换分组，展示每组事项数、风险数、已更新数和平均进度。
* 未关联项目的事项归入“BU 内部事项”，没有负责人的事项归入“未指定负责人”。
* 每个事项展示本周相对上周的进度差，并标识推进、持平、回退或无上周数据。
* 新增里程碑模式，以时间顺序汇总计划完成节点，并支持切换月份。
* 保留现有台账、详情、周进展维护、加载、空状态和错误状态。
* 桌面端和移动端均无页面级横向溢出，交互元素保留可见焦点状态。

## Acceptance Criteria

* [ ] 新建/编辑事项和周会选周的日历面板显示中文月份、星期和按钮。
* [ ] 周会事项可按负责人或项目形成独立分组，并显示汇总数字。
* [ ] 有连续两周数据时显示准确的进度差；缺少上一周数据时显示“上周无数据”。
* [ ] 里程碑视图按日期排序展示事项名称、负责人、状态、进度和计划完成日。
* [ ] 现有台账与周进展保存流程不回归。
* [ ] 前端构建、后端测试（若后端有改动）和关键 Playwright 用例通过。

## Definition of Done

* Tests added or updated for Chinese locale, owner grouping, week comparison and milestone view.
* Frontend typecheck/build passes.
* Browser inspection confirms desktop/mobile layout and date-picker locale.
* UI/API contract documentation is updated.

## Technical Approach

* 在 `frontend/src/main.ts` 为 Element Plus 配置 `zh-cn` locale。
* 在现有页面计算负责人/项目分组、指定周/上周进展差，不改变 API payload。
* 增加 `milestone` 页面模式，使用事项 `plannedCompletionDate` 构建月份时间线。
* 复用现有 Element Plus 组件、项目 CSS token 和 API 方法，不新增依赖。

## Decision (ADR-lite)

**Context**: 用户需要的是管理视图增强，现有数据已经包含周历史和计划完成日。

**Decision**: 采用纯前端派生方案；周会支持按负责人或项目汇总，里程碑使用事项计划完成日。

**Consequences**: 可快速上线且无数据迁移风险；本期不支持一个事项维护多个子里程碑。

## Out of Scope

* 一个事项下维护多个独立里程碑。
* 甘特图依赖关系、拖拽改期和关键路径计算。
* 自动生成或改写周报内容。

## Technical Notes

* Relevant UI contract: `.trellis/spec/frontend/key-matter-management-ui.md`
* Relevant backend contract: `.trellis/spec/backend/key-matter-management-contract.md`
* Primary UI: `frontend/src/views/KeyMattersView.vue`
* API types: `frontend/src/utils/api.ts`
* E2E: `frontend/tests/key-matters.spec.ts`
