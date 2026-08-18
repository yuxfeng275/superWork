# Email Management API Contract

## Scope

This contract covers personal Alibaba Cloud Enterprise Mail ingestion, per-user inbox access, previous-day summaries, DeepSeek generation, and WeCom delivery.

## Security Boundary

- Every `/api/emails/**` endpoint requires `email:view`; account mutation and manual sync use `email:manage` where method-level narrowing is needed.
- `userId` always comes from the JWT-populated `@RequestAttribute("userId")`.
- An email account has exactly one `ownerUserId`. Account, message, digest, sync, and push queries always include the current owner ID.
- Administrators do not gain implicit access to another employee's message body.
- App passwords are encrypted with AES-256-GCM using `EMAIL_CREDENTIAL_ENCRYPTION_KEY`; API responses expose only `credentialConfigured`.

## Provider and Sync Contract

- Provider is fixed to Alibaba Cloud Enterprise Mail. Users enter only `emailAddress` and `appPassword`.
- The adapter uses IMAPS, Inbox only, without changing server flags or deleting/moving mail.
- The first successful sync reads the last seven Shanghai calendar days. Later syncs use IMAP `UIDVALIDITY + UID` checkpoints.
- `(account_id, folder, uid_validity, uid)` is unique. Repeated and overlapping jobs are idempotent.
- Automatic sync runs hourly. `POST /api/emails/sync` starts a current-user job; `GET /api/emails/sync/status` returns `IDLE|RUNNING|SUCCESS|FAILED` plus timestamps/counts and a sanitized message.
- MIME HTML is converted to text. Attachments persist metadata only; attachment bytes, remote images, and active HTML are never stored or returned.

## API Shapes

### Account

- `GET /api/emails/account` -> `{ configured, enabled, provider, emailAddress, credentialConfigured, connectionStatus, lastTestedAt, lastSyncAt, lastSyncStatus, lastSyncMessage }`.
- `PUT /api/emails/account` body: `{ emailAddress, appPassword }`. A blank password preserves the existing credential only when an account already exists.
- `POST /api/emails/account/test` tests the current saved account and returns `{ success, message, testedAt }`.
- `DELETE /api/emails/account` disables/removes the current user's credential and stops future sync without exposing it.

### Inbox

- `GET /api/emails/messages?page=1&size=20&date=YYYY-MM-DD&keyword=...` returns a MyBatis page of current-user message summaries.
- `GET /api/emails/messages/{id}` returns current-user plain-text message detail. An ID owned by another user is reported as not found.
- Summary fields: `id, messageId, subject, fromName, fromAddress, receivedAt, preview, hasAttachments, attachmentCount`.
- Detail adds `toAddresses, ccAddresses, textBody, attachments[{fileName,contentType,size}]`.

### Digest

- `GET /api/emails/digests?date=YYYY-MM-DD` returns the current user's date digest or an explicit `EMPTY|PENDING` state.
- `POST /api/emails/digests/{date}/regenerate` asynchronously regenerates only the current user's digest.
- Digest response: `id, businessDate, status, generationMode, overview, mailCount, importantItems, todos, risks, replySuggestions, generatedAt, pushStatus, pushMessage`.
- Every structured item contains only a stored message ID belonging to the same user. Invalid model references are rejected.



## Intelligent Project Grouping

- `GET /api/emails/sender-company-groups` aggregates current-user mail by normalized sender domain; `senderDomain` filters the message query.

- The candidate set is the current active rows in `project`; model output may only reference those project IDs.
- Classification uses email subject, sender, and truncated plain-text body in batches of 15.
- The backend verifies current-user message IDs, existing project IDs, and confidence >= 0.65. Invalid, low-confidence, or missing matches persist as `UNGROUPED` with no project ID.
- New synchronized mail automatically starts grouping. `POST /api/emails/grouping` backfills ungrouped mail or regroups all; status and project-group counts are current-user scoped.
- Stored metadata includes project ID, status, method, confidence, reason, model, and grouped time. Project deletion sets the project ID to null.

## Per-message AI Interpretation

- `GET /api/emails/messages/{id}/interpretation` returns the stored current-user interpretation state.
- `POST /api/emails/messages/{id}/interpretation` generates or regenerates via the saved DeepSeek configuration and requires `email:sync`.
- Stored fields include status, structured JSON, model, sanitized error, and generated time. All reads and writes include `owner_user_id`.
- Structure: core summary, sender intent, key points, action items with deadline/priority, risks, and reply suggestion.
- A failed interpretation is persisted as `FAILED`; it never replaces the original body or pretends to be an AI result.

## Digest and Provider Failure Matrix

| Case | Mail ingestion | Digest | UI state |
| --- | --- | --- | --- |
| No mail | succeeds with zero | `EMPTY`, no model call | explicit empty state |
| IMAP auth/network failure | existing mail remains | existing digest remains | sanitized sync failure |
| DeepSeek disabled | unaffected | deterministic rules | `DEGRADED`, mode `RULES` |
| DeepSeek timeout/rate limit/invalid JSON | unaffected | deterministic rules | `DEGRADED`, retry available |
| WeCom unconfigured/no mapping | unaffected | stored normally | `NOT_CONFIGURED` or `UNMAPPED` |
| WeCom delivery failure | unaffected | stored normally | `FAILED`, independently retryable |

## Scheduling and Configuration

- `EMAIL_SYNC_CRON` defaults to hourly and `EMAIL_DIGEST_CRON` defaults to 08:00 Asia/Shanghai.
- DeepSeek and WeCom business settings are managed through the generic `/api/system/configs/email-integration` group and persisted in `system_config_item`.
- API Key and Secret use AES-256-GCM encryption; API responses expose only configured flags. Blank sensitive fields preserve existing encrypted values.
- Only the root `EMAIL_CREDENTIAL_ENCRYPTION_KEY` remains an infrastructure secret because database ciphertext cannot securely contain its own decryption root.
- No secret is logged, persisted in plaintext, returned by APIs, or included in errors.

## Good / Base / Bad Cases

- Good: a configured user syncs Inbox, sees a referenced structured digest, and receives a private WeCom overview.
- Base: no account and no mail return stable configured/empty states; the application starts with all integrations disabled.
- Bad: a user cannot fetch another user's message or trigger their sync; invalid email/password input is rejected; model and push failures never roll back stored mail.
