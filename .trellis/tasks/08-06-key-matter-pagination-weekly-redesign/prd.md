# brainstorm: 大事儿分页与周进展体验重构

## Goal

为大事儿台账增加可用的分页能力，并重构独立“更新周进展”与周会演示中的现场更新体验，使两个入口在信息层级、表单结构、操作反馈和视觉风格上与现有系统一致。

## What I already know

- 台账事项列表需要增加分页。
- 当前独立周进展抽屉的风格与布局不符合现有系统。
- 周会模式内的周进展编辑也需要同步调整，两个入口应使用同一套编辑口径。
- 不新增依赖，复用 Vue 3、Element Plus、现有 API 和页面 token。

## Assumptions (temporary)

- 分页优先复用后端现有的台账查询契约；若接口尚未分页，则先确认数据量与服务端改造范围。
- 周进展重构保留当前字段和提交接口，不改变业务契约。
- 视觉方向延续当前冷白管理驾驶舱，以信息分组与编辑效率为主，不做独立营销化界面。

## Open Questions

- None.

## Research Notes

### Existing implementation

- `GET /api/key-matters` returns a fully filtered array, so pagination can stay
  in the frontend without changing the API or duplicating overview requests.
- The standalone weekly drawer is a single vertical Element Plus form with four
  text areas and no visual distinction between outcome, risk, execution, and
  decision content.
- The meeting editor uses repeatable progress lines and a different layout from
  the drawer, even though both submit the same payload.
- Existing system pages favor restrained white surfaces, compact form controls,
  bordered sections, and persistent bottom actions.

### Feasible approaches

**Approach A: Structured weekly workspace (Recommended)**

- One shared information architecture for drawer and meeting editor.
- Header row contains week, status, and progress; the body emphasizes this
  week's outcomes, followed by risk/coordination signals and next actions.
- Meeting mode uses the same structure in a denser embedded variant.
- Fast to scan, preserves all fields, and fits the current management UI.

**Approach B: Three-step guided update**

- Split state/progress, weekly result, and next actions into sequential steps.
- Reduces initial density but slows down experienced users and meeting updates.

**Approach C: Edit-and-preview split view**

- Edit fields on the left and render the final weekly briefing on the right.
- Strong review experience but consumes more width and duplicates presentation
  behavior already available in meeting mode.

### Expansion and edge cases

- Keep drawer and meeting parity so later field or validation changes have one
  design contract.
- Reset pagination on filters and page-size changes; clamp the page after data
  refresh or deletion so users never land on an empty out-of-range page.
- Preserve current-week prefill, completed-status progress synchronization,
  submit payload, saving state, and server error feedback.

## Requirements (evolving)

- 台账使用前端分页，默认 10 条，支持 10/20/50 条切换；筛选或每页条数变化后回到第一页。
- 快速筛选与常规筛选都要与分页状态协同。
- 独立抽屉和周会模式采用“结构化周报工作台”：顶部显示周期、状态和进度，主区突出本周成果，其次是问题/风险与需协调/决策，底部为下一步行动。
- 抽屉使用完整布局，周会演示使用同结构的紧凑内嵌版，两者保持字段层级、文案、验证和提交反馈一致。
- 保留状态、进度、本周进展、问题/风险、下一步、需协调/决策字段。
- 覆盖 loading、saving、validation error、disabled、mobile 和键盘焦点状态。

## Acceptance Criteria (evolving)

- [ ] 台账列表显示总数、页码和每页条数，翻页后数据正确。
- [ ] 任一筛选变化都不会产生空白越界页。
- [ ] 两个周进展入口信息架构一致，且清晰区分“事项状态”、“成果”、“风险”与“下一步”。
- [ ] 现有周进展数据可正确回显，提交 payload 无变化。
- [ ] 桌面端与 390px 移动端无溢出和重叠。
- [ ] `npm run build` 与相关 Playwright 用例通过。
- [ ] 部署到 241 并验证线上 URL。

## Definition of Done

- Tests added/updated for pagination and both weekly-update entry points.
- Typecheck/build green and browser screenshots checked at desktop/mobile sizes.
- UI contract updated if behavior changes.
- Deployment artifact retained for rollback and deployed URL verified.

## Out of Scope (explicit)

- 暂不改变周进展后端字段或新增附件、评论、AI 生成等能力。
- 暂不重构大事儿详情、里程碑和周会阅读态。

## Technical Notes

- Primary implementation: `frontend/src/views/KeyMattersView.vue`.
- Existing regression suite: `frontend/tests/key-matters.spec.ts`.
- Existing UI contract: `.trellis/spec/frontend/key-matter-management-ui.md`.

## Decision (ADR-lite)

**Context**: The existing drawer and meeting editor submit the same weekly-update
payload but present different field structures and visual hierarchy.

**Decision**: Use Approach A, a structured weekly workspace. Keep one design
contract and shared semantic classes for both entry points, with full and compact
layout variants. Keep pagination client-side because the current endpoint returns
the complete filtered collection and the overview requires an unpaged dataset.

**Consequences**: No backend or API change is required. The frontend owns page
clamping and pagination reset behavior. Both weekly editors must be updated and
tested together whenever their shared contract changes.

## Implementation Plan

1. Add pagination state, derived page data, reset/clamp behavior, and list footer.
2. Rebuild the standalone weekly drawer as the full structured workspace.
3. Rebuild meeting inline editing as the compact variant with matching labels.
4. Update Playwright coverage and the key-matter UI contract.
5. Build, browser-check desktop/mobile, deploy to 241, and verify the URL.
