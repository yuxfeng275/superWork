# Project Management Contract

This document defines the project lifecycle behavior shared by the project API,
`ProjectService`, and database foreign-key relationships.

## Scenario: Delete a Project Without Losing Historical Records

### 1. Scope / Trigger

- Trigger: `DELETE /api/projects/{id}` for a project that may already be
  referenced by requirements, customer contacts, issues, project membership,
  BU execution data, or Yunxiao integration data.
- Implementation entry points:
  - `backend/src/main/java/com/bu/management/controller/ProjectController.java`
  - `backend/src/main/java/com/bu/management/service/ProjectService.java`
  - `backend/src/main/resources/db/migration/V1__init_schema.sql`
- The contract applies only to leaf projects. A project with child projects is
  not deletable.

### 2. Signatures

```http
DELETE /api/projects/{id}
Authorization: Bearer <accessToken>
```

```java
@Transactional(rollbackFor = Exception.class)
public void ProjectService.delete(Long id)
```

- Required permission: `org:edit`.
- Success response: `Result<Void>` with code `200`, message `删除成功`, and
  `data: null`.
- The endpoint request and response shapes must not expose database cleanup
  details.

### 3. Contracts

Deletion must run in one transaction and in this order:

1. Load `project.id`; reject a missing project.
2. Count rows where `project.parent_id = id`; reject a non-leaf project.
3. Set `requirement.project_id` to `NULL` for the project.
4. For contacts owned by the project, set referencing
   `requirement.customer_contact_id` values to `NULL`.
5. Set `issue.project_id` to `NULL` for the project.
6. Delete `customer_contact` rows owned by the project.
7. Delete the project.

Historical requirements and issues are retained. Project contacts are removed
because they cannot exist without a project. Tables whose project foreign key
declares `ON DELETE CASCADE` continue to use database-managed cleanup. Tables
whose project foreign key declares `ON DELETE SET NULL` continue to retain their
records.

Any cleanup or delete failure must roll back every preceding update. The API
must retain its existing permission and payload contract.

### 4. Validation & Error Matrix

| Condition | Service behavior | API behavior |
| --- | --- | --- |
| Project exists and has no children | Detach restrictive references and delete | `200`, `删除成功` |
| Project does not exist | Throw `RuntimeException("项目不存在")` | `400`, `项目不存在` |
| Project has one or more children | Throw `RuntimeException("存在子项目，无法删除")` | `400`, `存在子项目，无法删除` |
| Reference cleanup fails | Roll back the transaction | `400` through the current runtime-exception handler |
| Caller lacks `org:edit` | Service is not invoked | `403` |
| Caller is anonymous | Service is not invoked | `401` |

### 5. Good / Base / Bad Cases

Good:

- Deleting `全渠道云鹿SAAS / SAAS平台` retains the linked
  `SAAS平台多租户支持` requirement and sets its `project_id` to `NULL`.
- A leaf project with requirements, project contacts, and issues is deleted;
  requirements and issues remain with cleared references.

Base:

- A leaf project with no restrictive references is deleted successfully.
- Project member, BU direction, and Yunxiao cache rows with cascade foreign keys
  are removed by the database.

Bad:

- A parent project with child projects is rejected before any historical row is
  modified.
- Deleting the project first and catching the foreign-key exception is invalid;
  the current global runtime-exception handler returns `400` with a database
  constraint message and leaves the requested operation incomplete.

### 6. Tests Required

- `ProjectServiceTest.deleteDetachesHistoricalRecordsBeforeDeletingProject`
  asserts that restrictive-reference cleanup happens before project deletion.
- `ProjectServiceDatabaseTest.deleteLeafProjectDetachesHistoricalRecordsAndRemovesProjectContacts`
  must run against a relational database with active foreign keys and assert:
  - the project row is absent;
  - project contact rows are absent;
  - requirement rows remain with `project_id` and `customer_contact_id` null;
  - issue rows remain with `project_id` null.
- `ProjectServiceDatabaseTest.deleteLeafProjectWithoutHistoricalRecords`
  covers the base leaf-project case.
- `ProjectServiceDatabaseTest.deleteParentProjectRejectsBeforeDetachingHistoricalRecords`
  asserts that child-project validation happens before reference cleanup.

### 7. Wrong vs Correct

#### Wrong

```java
projectMapper.deleteById(id);
```

Direct deletion fails when `requirement`, `customer_contact`, or `issue` still
references the project.

#### Correct

```java
detachHistoricalRecords(id);
projectMapper.deleteById(id);
```

The detach and delete operations are enclosed by the same transaction so the
database never observes a partially cleaned project deletion.
