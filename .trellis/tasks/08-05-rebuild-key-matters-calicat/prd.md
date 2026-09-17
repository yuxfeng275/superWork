# 按 Calicat 重构大事儿管理

## Goal

以 Calicat 文件 `2084882079001350144` 的「大事儿」画布为唯一视觉与交互基准，重构现有 `/key-matters` 页面，让台账、周会、周会演示、里程碑、详情与周报更新形成完整的管理闭环。

## Requirements

- 保留现有大事儿 API、权限边界、创建/编辑/删除、周报更新和周环比逻辑，不新增后端接口或依赖。
- 台账模式对齐设计稿的四项概览、筛选栏和高密度事项列表。
- 周会模式提供大数字标题、四张业务概览卡、按负责人/按项目分组和事项简报卡。
- 周会模式新增演示视图：逐项浏览、键盘上一项/下一项、已更新事项阅读态、待更新事项可直接填写周报并保存切换。
- 里程碑模式按月显示节点概览和日期时间线，事项可进入详情。
- 详情保留事项总进度、关键事实、本周简报、交付窗口和历史周进展。
- 覆盖 loading、empty、error、disabled、hover、focus 与移动端状态，并遵守 `prefers-reduced-motion`。
- 视觉方向采用 Calicat 的冷白管理驾驶舱：靛蓝主色、语义化绿/橙/红、紧凑卡片层级；signature move 为可直接更新的逐事项周会演示台。

## Acceptance Criteria

- [ ] `/key-matters` 三个主模式与 Calicat 画布的信息层级一致。
- [ ] 周会模式可按负责人和项目切换，并正确展示已更新、待更新、风险和平均进度。
- [ ] 演示模式可逐项切换，已更新事项显示简报，待更新事项可暂存/保存并进入下一项。
- [ ] 台账、详情、周报维护、里程碑现有业务能力无回归。
- [ ] 桌面端与 390px 移动端无横向溢出，键盘操作和可见焦点通过验证。
- [ ] `npm run build` 与 `frontend/tests/key-matters.spec.ts` 通过。
- [ ] 本地浏览器截图核对通过；部署成功并验证项目环境 URL。

## Technical Notes

- 主要实现文件：`frontend/src/views/KeyMattersView.vue`。
- 测试文件：`frontend/tests/key-matters.spec.ts`。
- 复用 `BuKeyMatter` 与 `BuKeyMatterWeeklyUpdatePayload`，不改变跨层契约。
- Calicat 参考页面包含周会杂志排版、按负责人、按项目、已更新演示态和待更新演示态。
