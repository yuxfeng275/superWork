# Sales Opportunity Follow-Up Contract

## Scope

Sales opportunity follow-ups are append-only business records. They are not the
same as the opportunity `note` and are not sales-support worklogs.

## Persistence

Table `sales_opportunity_follow_up` stores:

- `opportunity_id`: parent opportunity ID.
- `follow_up_at`: business time of the follow-up.
- `follower`: display name entered for this record.
- `content`: required follow-up situation, outcome, risks, and conclusion.
- `status`: opportunity stage snapshot after this follow-up.
- `probability`: probability snapshot from 0 through 100.
- `next_follow_up`: next action/time snapshot.
- `created_at`: server creation time.

Records are returned by `follow_up_at DESC, created_at DESC`. Deleting an
opportunity deletes its follow-up rows and support-worklog rows.

## API

### `GET /api/sales-opportunities/{id}/follow-ups`

Returns all follow-up records for the opportunity in descending order. A missing
opportunity is an error.

### `POST /api/sales-opportunities/{id}/follow-ups`

Request:

```json
{
  "followUpAt": "2026-08-10T14:30:00",
  "follower": "系统管理员",
  "content": "客户认可一期范围，等待采购确认预算",
  "status": "商务谈判",
  "probability": 70,
  "nextFollowUp": "周五 15:00"
}
```

Validation:

- `follower`, `content`, and `status` must contain text.
- `probability` is required and must be between 0 and 100 inclusive.
- Missing `followUpAt` defaults to server current time.
- `nextFollowUp` is optional.

The operation is transactional: insert the immutable history record, then update
the opportunity's current `status`, `probability`, and `nextFollowUp` snapshot.
The opportunity `note` is never changed by this endpoint.

## Test Cases

- Good: valid content appends one record and updates current snapshot.
- Base: follow-up time omitted and server time is used.
- Bad: blank content writes neither history nor opportunity update.
