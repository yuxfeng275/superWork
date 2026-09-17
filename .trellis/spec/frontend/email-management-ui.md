# Email Management UI Contract

## Route and Visibility

- `/emails` is an authenticated MainLayout route titled `邮件管理` and appears in the Workbench navigation.
- Frontend visibility is convenience only; backend owner filtering and permissions are authoritative.

## Unconfigured State

- Explain that the first version supports Alibaba Cloud Enterprise Mail personal Inbox only.
- The binding form asks for email address and third-party client security password, not the normal web password.
- The security password field is masked, never prefilled, and cleared after save/test.
- Display connection test success/failure without rendering raw server errors or secrets.

## Configured State

- Header shows masked account identity, connection/sync state, last sync time, `立即同步`, and account settings.
- Daily digest defaults to yesterday in Asia/Shanghai and supports date navigation.
- Sections: overview, important mail, todos, risks, and reply suggestions. Referenced rows open the corresponding message drawer.
- Clearly label `AI`, `规则降级`, `待生成`, `空摘要`, and WeCom `已推送/未配置/未映射/失败` states.
- Inbox supports date/keyword filtering and pagination. Rows show sender, subject, received time, preview, and attachment metadata indicator.
- Message drawer renders plain text with CSS whitespace preservation; it never uses `v-html` for email content.


## Integration Configuration

- System-wide DeepSeek and WeCom settings live under `/system/configs`, not on `/emails`.
- The email page contains only personal mailbox and employee WeCom UserId settings.


## Message Reader

- Message detail uses a wide reading workspace rather than a narrow basic drawer.
- It provides previous/next navigation within the loaded Inbox page, position indicator, sender identity, full Shanghai timestamp, expandable full headers, Message-ID, and copyable metadata.
- Body text is rendered in a paper-like plain-text region; HTML, remote images, and scripts are never executed.
- Attachment cards display type, name, MIME type, and size as metadata only.
- The reader becomes full-width on mobile and keeps close/navigation actions accessible.



## Project Grouping

- Inbox grouping navigation is a real left sidebar on desktop, with a mode switch for `按项目` and `按发件人公司`.
- Company grouping aggregates the authenticated user's mail by normalized sender email domain and filters through `senderDomain` on the backend.
- On mobile, the left sidebar becomes a horizontally scrollable grouping area above the list.

- Inbox provides horizontal project group filters, including `全部邮件` and `未分组`, with current-user mail counts.
- Message rows and detail headers display the matched project; full headers show confidence and grouping reason.
- `智能分组` runs asynchronously with progress, success counts, failure handling, and a separate full regroup action.
- Selecting a group filters the server query by `projectId`; selecting `未分组` sends `ungrouped=true`.

## AI Interpretation and Digest Tabs

- The message reader has `邮件原文` and `AI 解读` tabs. First activation of AI interpretation generates automatically when no saved result exists.
- Saved results render core conclusion, sender intent, key points, action items, risks, reply draft, model, and generated time; users may regenerate.
- The daily digest uses persisted date-specific data and tabs: `摘要总览`, `重要邮件`, `待办事项`, `风险提醒`, and `回复建议`.
- Digest overview displays generation mode, saved model, generation time, mail count, and push status.

## Interaction and Error Contract

- Manual sync starts asynchronously, prevents duplicate clicks while running, polls status, and refreshes Inbox on success.
- Digest regeneration is separate from sync and must not block reading existing mail.
- Empty account, empty Inbox, empty day, loading, partial/degraded, and request failure all have distinct user-visible states.
- Mobile layout stacks digest sections and converts the reading drawer to full width.

## Required Tests

- Binding submits only `emailAddress` and the entered app password and clears the password afterward.
- Configured page renders digest and Inbox, opens a referenced message, and renders body as text.
- Manual sync calls the current-user endpoint and reflects running/success state.
- Empty and DeepSeek-degraded states remain usable.


## DingTalk-style AI Minutes

- Daily digest adds `议题归纳` and `决策进展` tabs alongside overview, important mail, todos, risks, and replies.
- Topic cards merge related emails, show `已完成/推进中/待确认`, summarize the topic, and link to source mail.
- Progress uses a vertical timeline with status-colored nodes and source evidence.
- Overview remains a visual executive summary; the full narrative is collapsed by default.

## Daily Digest Readability

- Overview uses a clear date header, status row, metric strip, conclusion block, and generation metadata.
- Category tabs use vertically stacked cards with title, explanation, metadata (sender/deadline/action), and an explicit “查看邮件” affordance.
- Empty states use category-specific copy and metrics remain visible above tabs.
