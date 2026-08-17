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
- DeepSeek and WeCom business settings are managed through `/api/emails/integration-config` and persisted in `email_integration_config`.
- API Key and Secret use AES-256-GCM encryption; API responses expose only configured flags. Blank sensitive fields preserve existing encrypted values.
- Only the root `EMAIL_CREDENTIAL_ENCRYPTION_KEY` remains an infrastructure secret because database ciphertext cannot securely contain its own decryption root.
- No secret is logged, persisted in plaintext, returned by APIs, or included in errors.

## Good / Base / Bad Cases

- Good: a configured user syncs Inbox, sees a referenced structured digest, and receives a private WeCom overview.
- Base: no account and no mail return stable configured/empty states; the application starts with all integrations disabled.
- Bad: a user cannot fetch another user's message or trigger their sync; invalid email/password input is rejected; model and push failures never roll back stored mail.
