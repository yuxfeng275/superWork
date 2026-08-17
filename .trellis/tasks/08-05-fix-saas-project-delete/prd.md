# 修复 SAAS 平台无法删除

## Goal

修复“项目管理”中删除“全渠道云鹿SAAS / SAAS平台”时被数据库外键拒绝的问题，同时保留已产生的历史需求和事项数据。

## Requirements

- `DELETE /api/projects/{id}` 的接口签名和权限要求保持不变。
- 删除无子项目的项目时，先解除需求、事项等历史记录对该项目的引用，再删除项目。
- 被解除关联的需求和事项必须保留；需求关联的客户联系人引用需在联系人删除前清空。
- 项目成员及其他配置中已声明 `ON DELETE CASCADE` 的数据继续由数据库清理。
- 有子项目或项目不存在时，继续拒绝删除并返回现有业务错误。

## Acceptance Criteria

- [x] Good：存在直接关联需求的“SAAS平台”可以删除，需求仍保留且 `project_id` 变为 `NULL`。
- [x] Good：存在关联事项或客户联系人的叶子项目可以删除，历史事项保留，项目联系人安全清理。
- [x] Base：无任何关联数据的叶子项目可以删除。
- [x] Bad：存在子项目的项目仍不可删除。
- [x] 后端定向测试和完整测试通过。

## Technical Notes

- 根因是 `requirement.project_id`、`customer_contact.project_id`、`issue.project_id` 使用限制型外键，而原实现直接删除 `project`。
- 删除动作必须保持事务性；任一步失败时整体回滚。
- 本任务不修改前端 API 契约，也不删除历史需求/事项。
