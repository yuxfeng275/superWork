# Sales Opportunity Follow-Up UI

## Scope

The opportunity follow action creates history records. It must not overwrite the
opportunity remark or use the generic opportunity update API.

## Follow-Up Dialog

- Title is `商机跟进记录`.
- Follow-up time defaults to the current local date and time.
- Follower defaults to the authenticated user's real name and remains editable.
- Content is required and supports up to 1000 characters.
- Stage, probability, and next follow-up represent the new current snapshot.
- Saving calls `POST /api/sales-opportunities/{id}/follow-ups`.
- On success, refresh both opportunity rows and follow-up history, clear only the
  content field, and keep the dialog open for verification or another entry.
- History is displayed newest first and includes follower, follow-up time,
  content, stage, probability, and next follow-up.

## Opportunity List And Detail

The list omits the `下次跟进` column. `createdAt` is displayed as
`YYYY-MM-DD HH:mm` when time is available and as `YYYY-MM-DD` for date-only
values; ISO `T` and backend space-separated values are both accepted.

Opening detail loads support worklogs and follow-up history in parallel. The
follow-up timeline is separate from the opportunity remark and the support-hours
panel.

## States

- Empty history: display `暂无跟进记录`.
- Loading error: clear stale history and show `跟进记录加载失败`.
- Validation: missing follower or content blocks submission with a warning.
- Save error: preserve form content and show `跟进记录保存失败`.
- Mobile: form pairs collapse to one column and timeline remains internally
  scrollable.

## Test Points

- Existing history appears when the dialog opens.
- Follower defaults to the current authenticated user and can be edited.
- A new record is posted to the follow-up endpoint, not the opportunity PUT.
- New and previous records remain visible after saving and in opportunity detail.
