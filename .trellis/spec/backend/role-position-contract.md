# Role Position Contract

This project uses the 2026 mid-year position model as the canonical role catalog.

## Canonical Role Codes

| Code | Label | Sequence |
| --- | --- | --- |
| `DIRECTOR` | 总监 | 管理序列 |
| `DEPUTY_DIRECTOR` | 副总监 | 管理序列 |
| `BUSINESS_OWNER` | 经营负责人 | 管理序列 |
| `EFFECTIVENESS_OWNER` | 成效负责人 | 管理序列 |
| `SOLUTION_MANAGER` | 解决方案经理 | 执行序列 |
| `TECH_ARCHITECT` | 技术架构师 | 执行序列 |
| `FULL_STACK_ENGINEER` | 全栈工程师 | 执行序列 |
| `QUALITY_ENGINEER` | 质量工程师 | 执行序列 |
| `AI_OPERATIONS_ENGINEER` | 智能运营工程师 | 执行序列 |
| `AI_CUSTOMER_SERVICE` | 智能客服专员 | 执行序列 |
| `EXPERIENCE_CONTENT_DESIGNER` | 体验与内容设计师 | 执行序列 |

## Backend Rules

- `user.role` stores one canonical role code.
- `sys_role.code` stores the same canonical role code for permissions.
- The 11 canonical roles are system defaults; role listing must initialize missing default rows idempotently.
- Default role codes and names are fixed. Default roles may be enabled/disabled or authorized, but must not be deleted.
- Creating roles is limited to the canonical default role catalog; arbitrary custom role codes are rejected.
- Default role initialization also backfills missing menu and permission links without deleting existing custom authorization.
- Management sequence roles receive all active menus and permissions by default; execution sequence roles receive the baseline workbench, requirement, task, base-category, customer, and statistics access defined by the role catalog.
- User create/update must synchronize `sys_user_role` from `user.role`.
- Project owner selection is limited to management sequence roles plus `SOLUTION_MANAGER`.
- Workflow allowed roles store Chinese labels, normalized from both canonical codes and legacy role labels.
- Legacy roles remain only as compatibility aliases and inactive `sys_role` rows after migration.
