# Bug Analysis: SAAS 平台无法删除

## 1. Root Cause Category

- **Category**: B + D — Cross-Layer Contract / Test Coverage Gap
- **Specific Cause**: 项目删除只检查了子项目，未处理 `requirement`、`customer_contact`、`issue` 对 `project` 的限制型外键；既有单元测试也未启用真实外键，因而没有暴露数据库拒绝删除的问题。

## 2. Why Fixes Failed

1. 前端只显示通用“删除失败”，隐藏了数据库约束信息，但前端不是根因。
2. 只验证 `projectMapper.deleteById` 被调用无法证明真实数据库能完成删除。

## 3. Prevention Mechanisms

| Priority | Mechanism | Specific Action | Status |
| --- | --- | --- | --- |
| P0 | Integration test | 使用启用外键的 H2 覆盖项目、需求、联系人和事项删除结果 | DONE |
| P0 | Executable contract | 新增项目删除顺序、保留策略和错误矩阵 | DONE |
| P1 | Review checklist | 新增/修改删除逻辑时扫描所有指向目标表的外键 | DONE in spec |

## 4. Systematic Expansion

- **Similar Issues**: `BusinessLineService.delete` 仍直接删除业务线，未来调整时应先审计所有业务线外键。
- **Design Improvement**: 对需要保留历史记录的关联统一使用显式置空语义；对纯派生数据使用数据库级 `ON DELETE CASCADE`。
- **Process Improvement**: 删除类缺陷必须包含至少一个启用真实外键的数据库测试，不能只依赖 Mapper mock。

## 5. Knowledge Capture

- [x] 新增 `.trellis/spec/backend/project-management-contract.md`
- [x] 更新 `.trellis/spec/backend/index.md`
- [x] 新增 `ProjectServiceDatabaseTest`
- [x] 当前仓库不存在 `src/templates/markdown/spec/`，无需同步模板
- [ ] 由用户在确认工作区其他改动后统一提交
