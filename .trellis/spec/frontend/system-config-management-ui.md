# System Configuration Management UI

- Route: `/system/configs`, titled `配置管理`, visible to management roles under the System navigation section.
- Left navigation displays reusable configuration groups and configured-item counts.
- The editor renders controls from backend metadata: switches for `BOOLEAN`, masked inputs for `PASSWORD`, and text inputs for other types.
- Sensitive inputs always load empty. Configured secrets show only “已配置；留空保持不变” and are cleared after save.
- Integration-specific test actions may appear for a group, but item editing remains generic.
- `/emails` contains only personal mailbox, digest, inbox, and employee WeCom mapping behavior; system-wide provider configuration does not appear there.
