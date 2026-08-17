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

- Management roles see the global DeepSeek and WeCom configuration panel on `/emails`; other employees do not.
- API Key and Secret inputs are always blank on load and cleared after save. Configured secrets display status flags only.
- Saving supports enabled flags, root service URLs, model, CorpId, AgentId, public base URL, and optional secret replacement.
- DeepSeek and WeCom connection tests display sanitized results and never expose credentials.

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
