# Role Position UI

Frontend role-facing pages must use `frontend/src/constants/roles.ts` as the single role catalog.

## UI Rules

- User Management displays users under the 11 canonical roles and separates management/execution sequence metadata.
- Role Management treats the same 11 roles as fixed defaults, grouped by management sequence and execution sequence.
- Role Management must not expose arbitrary custom role creation for new roles; missing defaults are initialized as default roles.
- Historical non-canonical roles may be shown only in a compatibility section, not as primary role choices.
- Project owner selects users whose role code is in the management sequence or `SOLUTION_MANAGER`.
- Workflow role options use canonical Chinese role labels plus `系统自动`.
- Legacy role codes may be displayed through compatibility labels, but new create/edit flows should only offer canonical roles.
