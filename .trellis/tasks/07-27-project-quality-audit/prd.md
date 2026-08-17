# Project Quality Audit and Fixes

## Goal

Audit the current full-stack application, fix reproducible quality issues, and verify the running system end to end.

## Requirements

- Preserve existing uncommitted work and current business data.
- Run the repository's available frontend and backend checks.
- Inspect critical authentication, authorization, project, task, user, and workflow paths.
- Fix confirmed functional, security, or data-consistency defects with focused changes.
- Add regression coverage for every behavior changed.

## Acceptance Criteria

- [ ] Frontend production build passes.
- [ ] Frontend Playwright suite passes.
- [ ] Backend Maven test suite passes.
- [ ] Running frontend and backend return successful HTTP responses.
- [ ] Fixed critical workflows are verified in a real browser or through real APIs.
- [ ] Remaining risks are documented with evidence.

## Technical Notes

- Treat the dirty worktree as the baseline and do not revert unrelated changes.
- Prefer small root-cause fixes over broad refactors.
- Do not change database schema unless a confirmed defect requires it.
