# 大事儿参与人及负责人周进度反馈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加大事儿多参与人关系和动态访问授权，让负责人可反馈本人事项周进度、参与人可查看全部事项但保持只读，管理员继续拥有全部管理权限。

**Architecture:** 通过 Flyway V33 建立 `bu_key_matter_participant` 关系表，并在事项事务中维护负责人必在参与人集合的不变量。读取和反馈能力将当前参与人/负责人关系分别与 `bu:key-matter:view` / `bu:key-matter:feedback` RBAC 权限组合；管理员管理权限必须同时满足用户名为 `admin` 或 `yufeng` 以及 `bu:key-matter:manage`。周进度写路径继续在事项行锁内判断当前 `ownerId`，负责人变化立即转移写权限。前端通过 `/api/key-matters/access` 控制菜单和路由，并在大事儿页面按 `canManageAll` 与 `matter.ownerId === currentUser.id` 控制操作入口。

**Tech Stack:** Spring Boot 3.2、MyBatis Plus、MySQL/Flyway、JUnit 5/Mockito/H2、Vue 3、Pinia、Vue Router、Element Plus、Playwright

**Scope:** 本次只处理大事儿访问、参与人和负责人周进度权限；不调整投入比例、不调整其他业务模块、不修改既有完成事项停止周报规则。

---

## File Map

### Backend

- Create: `backend/src/main/resources/db/migration/V33__add_key_matter_participants_and_access.sql` — 参与关系表、权限初始化、旧负责人回填。
- Create: `backend/src/main/java/com/bu/management/entity/BuKeyMatterParticipant.java` — 参与关系实体。
- Create: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterParticipantMapper.java` — 参与关系查询和批量维护。
- Create: `backend/src/main/java/com/bu/management/vo/BuKeyMatterParticipantView.java` — 参与人响应。
- Create: `backend/src/main/java/com/bu/management/vo/BuKeyMatterAccessView.java` — 能力响应。
- Create: `backend/src/main/java/com/bu/management/exception/ForbiddenOperationException.java` — 稳定返回 403 的领域异常。
- Create: `backend/src/main/java/com/bu/management/service/BuKeyMatterAccessService.java` — 能力计算、读权限、管理权限、负责人反馈权限。
- Modify: `backend/src/main/java/com/bu/management/dto/BuKeyMatterRequest.java` — 增加 `participantIds`。
- Modify: `backend/src/main/java/com/bu/management/vo/BuKeyMatterView.java` — 增加 `participants`。
- Modify: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java` — 增加参与/负责人关系查询和锁查询复用。
- Modify: `backend/src/main/java/com/bu/management/service/BuKeyMatterService.java` — 事务同步参与关系、返回参与人、负责人写权限校验。
- Modify: `backend/src/main/java/com/bu/management/controller/BuKeyMatterController.java` — 去除固定白名单，改为方法权限和领域授权。
- Modify: `backend/src/main/java/com/bu/management/exception/GlobalExceptionHandler.java` — 处理 `ForbiddenOperationException` 为 HTTP 403。
- Test: `backend/src/test/java/com/bu/management/service/BuKeyMatterAccessServiceTest.java` — 四类用户能力矩阵。
- Test: `backend/src/test/java/com/bu/management/service/BuKeyMatterParticipantServiceTest.java` — 参与人同步、负责人回填、停用用户校验。
- Modify: `backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java` — 负责人写入和非负责人拒绝。
- Modify: `backend/src/test/java/com/bu/management/service/BuKeyMatterLockIntegrationTest.java` — 负责人变化与周报写入竞态。
- Modify: `backend/src/test/java/com/bu/management/controller/ControllerSecurityContractTest.java` — 新权限注解和 access API。
- Modify: `backend/src/test/java/com/bu/management/security/PermissionInterceptorUsernameTest.java` — 管理员用户名 + 权限组合。

### Frontend

- Modify: `frontend/src/utils/api.ts` — access、participant 类型和请求方法。
- Modify: `frontend/src/stores/auth.ts` — 大事儿能力状态和刷新。
- Modify: `frontend/src/router/index.ts` — 动态大事儿路由守卫。
- Modify: `frontend/src/layouts/MainLayout.vue` — 动态菜单显示。
- Modify: `frontend/src/views/KeyMattersView.vue` — 参与人表单/展示和负责人写入口控制。
- Modify: `frontend/tests/navigation-permissions.spec.ts` — 负责人、参与人、无关系用户菜单/路由。
- Modify: `frontend/tests/key-matters.spec.ts` — 参与人展示、负责人写入口、参与人只读、403 恢复。
- Create: `frontend/tests/key-matter-participant-access.spec.ts` — 独立能力与操作权限 E2E，避免污染既有管理员 fixtures。

### Contracts

- Modify: `.trellis/spec/backend/key-matter-management-contract.md` — 动态访问、参与人和负责人反馈契约。
- Modify: `.trellis/spec/frontend/key-matter-management-ui.md` — 能力状态、参与人展示、按钮权限和测试点。

---

## Task 1: Database, DTO/VO and Access Error Contracts

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__add_key_matter_participants_and_access.sql`
- Create: `backend/src/main/java/com/bu/management/entity/BuKeyMatterParticipant.java`
- Create: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterParticipantMapper.java`
- Create: `backend/src/main/java/com/bu/management/vo/BuKeyMatterParticipantView.java`
- Create: `backend/src/main/java/com/bu/management/vo/BuKeyMatterAccessView.java`
- Create: `backend/src/main/java/com/bu/management/exception/ForbiddenOperationException.java`
- Modify: `backend/src/main/java/com/bu/management/dto/BuKeyMatterRequest.java`
- Modify: `backend/src/main/java/com/bu/management/vo/BuKeyMatterView.java`
- Modify: `backend/src/main/java/com/bu/management/exception/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/bu/management/controller/ControllerSecurityContractTest.java`

- [ ] **Step 1: Write migration/schema contract tests first**

Add a migration contract test or extend the existing database integration fixture so it asserts the exact participant schema:

```java
@Test
void participantTableHasUniqueMatterUserRelationship() {
    jdbcTemplate.execute("""
        CREATE TABLE bu_key_matter_participant (
            id BIGINT PRIMARY KEY AUTO_INCREMENT,
            key_matter_id BIGINT NOT NULL,
            user_id BIGINT NOT NULL,
            created_at TIMESTAMP,
            updated_at TIMESTAMP,
            UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id)
        )
        """);

    jdbcTemplate.update("INSERT INTO bu_key_matter_participant (key_matter_id, user_id) VALUES (11, 7)");
    assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO bu_key_matter_participant (key_matter_id, user_id) VALUES (11, 7)"))
            .isInstanceOf(DataAccessException.class);
}
```

Add the controller contract assertion:

```java
@Test
void keyMatterControllerExposesAccessAndUsesPermissionAnnotations() throws Exception {
    Method accessMethod = BuKeyMatterController.class.getDeclaredMethod(
            "access", Long.class, String.class);
    assertThat(accessMethod.isAnnotationPresent(GetMapping.class)).isTrue();
    assertThat(accessMethod.getAnnotation(RequirePermission.class).value())
            .containsExactlyInAnyOrder("bu:key-matter:view", "bu:key-matter:feedback", "bu:key-matter:manage");
}
```

- [ ] **Step 2: Run tests and confirm the missing contract fails**

```bash
cd backend
mvn -Dtest=ControllerSecurityContractTest test
```

Expected: compilation or assertion failure because access method, migration, and new types do not exist.

- [ ] **Step 3: Add V33 migration**

Create `V33__add_key_matter_participants_and_access.sql` with this exact behavior:

```sql
CREATE TABLE bu_key_matter_participant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    key_matter_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id),
    INDEX idx_key_matter_participant_user (user_id),
    CONSTRAINT fk_key_matter_participant_matter
        FOREIGN KEY (key_matter_id) REFERENCES bu_key_matter(id) ON DELETE CASCADE,
    CONSTRAINT fk_key_matter_participant_user
        FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO bu_key_matter_participant (key_matter_id, user_id)
SELECT id, owner_id FROM bu_key_matter;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:key-matter:view', '查看BU大事儿', '查看全部大事儿和周会视图', 'menu', id
FROM sys_menu WHERE path = '/key-matters'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT INTO sys_permission (code, name, description, type, menu_id)
SELECT 'bu:key-matter:feedback', '反馈大事儿周进度', '负责人反馈本人负责事项的周进度', 'button', id
FROM sys_menu WHERE path = '/key-matters'
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), status = 1;

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission
  ON permission.code IN ('bu:key-matter:view', 'bu:key-matter:feedback')
WHERE role.status = 1;
```

Do not grant `bu:key-matter:manage` to new roles. Keep the existing manage grant unchanged.

- [ ] **Step 4: Add Java types**

`BuKeyMatterParticipant` must use MyBatis Plus mapping:

```java
@Data
@TableName("bu_key_matter_participant")
public class BuKeyMatterParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long keyMatterId;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`BuKeyMatterParticipantMapper` extends `BaseMapper<BuKeyMatterParticipant>`.

`BuKeyMatterParticipantView` contains `Long userId`, `String username`, `String realName`.

`BuKeyMatterAccessView` contains primitive-safe booleans:

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BuKeyMatterAccessView {
    private boolean canAccess;
    private boolean canManageAll;
    private boolean canFeedbackOwn;
}
```

Add `ForbiddenOperationException extends RuntimeException` with a message constructor.

- [ ] **Step 5: Extend request/view types**

Add to `BuKeyMatterRequest`:

```java
private List<Long> participantIds;
```

Add to `BuKeyMatterView`:

```java
private List<BuKeyMatterParticipantView> participants = new ArrayList<>();
```

- [ ] **Step 6: Add explicit HTTP 403 handling**

Before the generic `RuntimeException` handler in `GlobalExceptionHandler` add:

```java
@ExceptionHandler(ForbiddenOperationException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public Result<Void> handleForbiddenOperationException(ForbiddenOperationException e) {
    log.warn("业务权限拒绝: {}", e.getMessage());
    return Result.error(403, e.getMessage());
}
```

- [ ] **Step 7: Run schema/type tests**

```bash
cd backend
mvn -Dtest=ControllerSecurityContractTest test
```

Expected: PASS with the migration/types/error contract present.

- [ ] **Step 8: Commit the contract layer**

```bash
git add backend/src/main/resources/db/migration/V33__add_key_matter_participants_and_access.sql \
  backend/src/main/java/com/bu/management/entity/BuKeyMatterParticipant.java \
  backend/src/main/java/com/bu/management/mapper/BuKeyMatterParticipantMapper.java \
  backend/src/main/java/com/bu/management/vo/BuKeyMatterParticipantView.java \
  backend/src/main/java/com/bu/management/vo/BuKeyMatterAccessView.java \
  backend/src/main/java/com/bu/management/exception/ForbiddenOperationException.java \
  backend/src/main/java/com/bu/management/dto/BuKeyMatterRequest.java \
  backend/src/main/java/com/bu/management/vo/BuKeyMatterView.java \
  backend/src/main/java/com/bu/management/exception/GlobalExceptionHandler.java
 git commit -m "feat(key-matters): add participant relation contract"
```

---

## Task 2: Backend Access, Participant Synchronization and Owner Feedback Authorization

**Files:**
- Create: `backend/src/main/java/com/bu/management/service/BuKeyMatterAccessService.java`
- Modify: `backend/src/main/java/com/bu/management/service/BuKeyMatterService.java`
- Modify: `backend/src/main/java/com/bu/management/controller/BuKeyMatterController.java`
- Modify: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterMapper.java`
- Modify: `backend/src/main/java/com/bu/management/mapper/BuKeyMatterParticipantMapper.java`
- Create: `backend/src/test/java/com/bu/management/service/BuKeyMatterAccessServiceTest.java`
- Create: `backend/src/test/java/com/bu/management/service/BuKeyMatterParticipantServiceTest.java`
- Modify: `backend/src/test/java/com/bu/management/service/BuKeyMatterServiceTest.java`
- Modify: `backend/src/test/java/com/bu/management/service/BuKeyMatterLockIntegrationTest.java`

- [ ] **Step 1: Write access matrix tests first**

Use mocked `SysRoleService`, participant mapper and matter mapper. Cover these exact cases:

```java
@Test
void accessForOwnerCanReadAndFeedbackButCannotManageAll() {
    when(sysRoleService.getPermissionCodesByUserId(7L))
            .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
    when(participantMapper.existsByUserId(7L)).thenReturn(true);
    when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

    BuKeyMatterAccessView access = service.resolveAccess(7L, "owner");

    assertThat(access).isEqualTo(new BuKeyMatterAccessView(true, false, true));
}

@Test
void accessForParticipantIsReadOnly() {
    when(sysRoleService.getPermissionCodesByUserId(16L))
            .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
    when(participantMapper.existsByUserId(16L)).thenReturn(true);
    when(matterMapper.existsByOwnerId(16L)).thenReturn(false);

    assertThat(service.resolveAccess(16L, "participant"))
            .isEqualTo(new BuKeyMatterAccessView(true, false, false));
}

@Test
void accessForAdminRequiresManagePermission() {
    when(sysRoleService.getPermissionCodesByUserId(1L))
            .thenReturn(List.of("bu:key-matter:manage"));

    assertThat(service.resolveAccess(1L, "admin"))
            .isEqualTo(new BuKeyMatterAccessView(true, true, true));
}

@Test
void unrelatedUserCannotReadOrFeedback() {
    when(sysRoleService.getPermissionCodesByUserId(20L))
            .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
    when(participantMapper.existsByUserId(20L)).thenReturn(false);
    when(matterMapper.existsByOwnerId(20L)).thenReturn(false);

    assertThat(service.resolveAccess(20L, "unrelated"))
            .isEqualTo(new BuKeyMatterAccessView(false, false, false));
    assertThatThrownBy(() -> service.requireReadAccess(20L, "unrelated"))
            .isInstanceOf(ForbiddenOperationException.class)
            .hasMessage("无权访问大事儿");
}
```

Add `requireFeedback` tests for current owner success, participant rejection (`仅事项负责人可反馈周进度`), and admin success only with manage permission.

- [ ] **Step 2: Run access tests and confirm RED**

```bash
cd backend
mvn -Dtest=BuKeyMatterAccessServiceTest test
```

Expected: compilation failure because the access service and mapper methods do not exist.

- [ ] **Step 3: Implement access service**

Use explicit permission checks from `SysRoleService.getPermissionCodesByUserId(userId)`. The core implementation must follow:

```java
private boolean hasPermission(Long userId, String code) {
    return userId != null
            && sysRoleService.getPermissionCodesByUserId(userId).contains(code);
}

public BuKeyMatterAccessView resolveAccess(Long userId, String username) {
    boolean canManageAll = ("admin".equals(username) || "yufeng".equals(username))
            && hasPermission(userId, "bu:key-matter:manage");
    boolean canView = canManageAll || hasPermission(userId, "bu:key-matter:view");
    boolean isParticipant = userId != null && participantMapper.existsByUserId(userId);
    boolean canAccess = canManageAll || (canView && isParticipant);
    boolean canFeedbackOwn = canManageAll
            || (hasPermission(userId, "bu:key-matter:feedback")
                && userId != null && matterMapper.existsByOwnerId(userId));
    return new BuKeyMatterAccessView(canAccess, canManageAll, canFeedbackOwn);
}
```

`requireReadAccess` throws `ForbiddenOperationException("无权访问大事儿")` unless `canAccess`. `requireManageAll` throws `ForbiddenOperationException("仅管理员可管理大事儿")` unless `canManageAll`. `requireFeedback` throws `ForbiddenOperationException("仅事项负责人可反馈周进度")` unless admin/manage or `matter.getOwnerId().equals(userId)` with feedback permission.

- [ ] **Step 4: Add mapper relationship methods**

Add to `BuKeyMatterParticipantMapper`:

```java
@Select("SELECT COUNT(1) > 0 FROM bu_key_matter_participant WHERE user_id = #{userId}")
boolean existsByUserId(@Param("userId") Long userId);
```

Add to `BuKeyMatterMapper`:

```java
@Select("SELECT COUNT(1) > 0 FROM bu_key_matter WHERE owner_id = #{userId}")
boolean existsByOwnerId(@Param("userId") Long userId);
```

Add batch list/delete methods using MyBatis Plus wrappers in the service, not string concatenation.

- [ ] **Step 5: Add service participant synchronization tests**

Test these cases before implementation:

```java
@Test
void createDeduplicatesParticipantsAndAlwaysAddsOwner() { /* request owner 7, ids [7,16,16]; assert [7,16] */ }

@Test
void updateRemovesOldParticipantsAndAddsNewOwner() { /* old [7,16], request owner 21 ids [21]; assert [21] */ }

@Test
void inactiveParticipantIsRejected() {
    assertThatThrownBy(() -> service.validateParticipantIds(List.of(7L, 20L)))
        .hasMessage("参与人不存在或已停用");
}
```

The tests must assert mapper delete/insert calls and returned participant view data.

- [ ] **Step 6: Implement transactional participant sync and response loading**

Extend `BuKeyMatterService` constructor with `BuKeyMatterParticipantMapper` and `BuKeyMatterAccessService`.

Implement normalization:

```java
private List<Long> normalizeParticipantIds(BuKeyMatterRequest request) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    if (request.getParticipantIds() != null) {
        request.getParticipantIds().stream().filter(Objects::nonNull).forEach(ids::add);
    }
    ids.add(request.getOwnerId());
    return List.copyOf(ids);
}
```

Validate each selected user with `userMapper.selectBatchIds`; reject missing/disabled with exact `参与人不存在或已停用`. Keep owner validation and project validation unchanged.

After `matterMapper.insert/updateById`, synchronize relation rows in the same `@Transactional` method:

```java
private void syncParticipants(Long matterId, List<Long> participantIds) {
    participantMapper.delete(new LambdaQueryWrapper<BuKeyMatterParticipant>()
            .eq(BuKeyMatterParticipant::getKeyMatterId, matterId));
    participantIds.forEach(userId -> {
        BuKeyMatterParticipant participant = new BuKeyMatterParticipant();
        participant.setKeyMatterId(matterId);
        participant.setUserId(userId);
        participantMapper.insert(participant);
    });
}
```

Use a batch insert if the local mapper pattern supports it; preserve the unique constraint as final protection.

Load participants in `buildViews`: fetch all relation rows for matter IDs, then batch users and map `BuKeyMatterParticipantView` in relation order. `BuKeyMatterView.participants` must always be non-null.

- [ ] **Step 7: Add access checks to service/controller paths**

Change controller annotations as follows:

```java
@GetMapping("/access")
@RequirePermission({"bu:key-matter:view", "bu:key-matter:feedback", "bu:key-matter:manage"})
public Result<BuKeyMatterAccessView> access(
        @RequestAttribute("userId") Long userId,
        @RequestAttribute("username") String username) {
    return Result.success(accessService.resolveAccess(userId, username));
}
```

For list/get/meeting, require read access using request attributes before service reads. For create/update/delete, require manage all. For weekly upsert/delete:

1. lock matter with `findMatterForUpdate`;
2. call `accessService.requireFeedback(matter, userId, username)`;
3. retain existing completed-matter guard and weekly logic;
4. only then write.

Avoid calling `findMatter` before the lock for feedback writes. The owner check must observe the locked current owner.

- [ ] **Step 8: Run backend targeted tests**

```bash
cd backend
mvn -Dtest=BuKeyMatterAccessServiceTest,BuKeyMatterParticipantServiceTest,BuKeyMatterServiceTest,ControllerSecurityContractTest test
```

Expected: all pass, including previous completed-week tests.

- [ ] **Step 9: Add real owner-change concurrency test**

Extend `BuKeyMatterLockIntegrationTest`:

- transaction A locks matter 11, changes owner from 7 to 21, updates and holds before commit;
- transaction B invokes weekly upsert as user 7;
- B remains blocked while A holds lock;
- after release B returns HTTP/domain `仅事项负责人可反馈周进度` and weekly row count remains unchanged;
- user 21 can then submit successfully.

- [ ] **Step 10: Commit backend implementation**

```bash
git add backend/src/main backend/src/test backend/src/main/resources/db/migration/V33__add_key_matter_participants_and_access.sql
git commit -m "feat(key-matters): authorize participants and owner feedback"
```

---

## Task 3: Frontend API, Access Store, Route and Menu Authorization

**Files:**
- Modify: `frontend/src/utils/api.ts`
- Modify: `frontend/src/stores/auth.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/layouts/MainLayout.vue`
- Test: `frontend/tests/navigation-permissions.spec.ts`
- Create: `frontend/tests/key-matter-participant-access.spec.ts`

- [ ] **Step 1: Add frontend RED navigation tests**

Add three user fixtures to a new test file with mocked `/api/key-matters/access`:

```ts
const ownerAccess = { canAccess: true, canManageAll: false, canFeedbackOwn: true }
const participantAccess = { canAccess: true, canManageAll: false, canFeedbackOwn: false }
const unrelatedAccess = { canAccess: false, canManageAll: false, canFeedbackOwn: false }
```

Tests must initially fail against the current fixed username route:

```ts
test('负责人可以看到大事儿菜单并进入页面', async ({ page }) => { /* user shijiale, access owner, expect menu + /key-matters */ })
test('参与人可以查看大事儿但没有反馈能力', async ({ page }) => { /* access participant, expect menu + route */ })
test('无关系用户不能看到或进入大事儿', async ({ page }) => { /* access false, expect no menu and redirect */ })
```

- [ ] **Step 2: Run RED tests**

```bash
cd frontend
npx playwright test tests/key-matter-participant-access.spec.ts
```

Expected: failures because router/layout still use `allowedUsernames: ['admin','yufeng']`.

- [ ] **Step 3: Add API types and method**

In `frontend/src/utils/api.ts` add:

```ts
export interface KeyMatterAccess {
  canAccess: boolean
  canManageAll: boolean
  canFeedbackOwn: boolean
}

export interface BuKeyMatterParticipant {
  userId: number
  username: string
  realName: string
}
```

Add `participants?: BuKeyMatterParticipant[]` to `BuKeyMatter`, `participantIds?: number[]` to `BuKeyMatterPayload`, and:

```ts
async getKeyMatterAccess(): Promise<KeyMatterAccess> {
  return this.request<KeyMatterAccess>('/api/key-matters/access')
}
```

- [ ] **Step 4: Add access state to auth store**

Add:

```ts
export interface KeyMatterAccess {
  canAccess: boolean
  canManageAll: boolean
  canFeedbackOwn: boolean
}

const keyMatterAccess = ref<KeyMatterAccess | null>(null)
const loadKeyMatterAccess = async (force = false) => {
  if (keyMatterAccess.value && !force) return keyMatterAccess.value
  try {
    keyMatterAccess.value = await api.getKeyMatterAccess()
  } catch {
    keyMatterAccess.value = { canAccess: false, canManageAll: false, canFeedbackOwn: false }
  }
  return keyMatterAccess.value
}
```

Return `keyMatterAccess` and `loadKeyMatterAccess`; clear `keyMatterAccess` in `logout`. Do not use role/username alone for ordinary access.

- [ ] **Step 5: Make route guard async and capability-based**

Replace key-matter route metadata with `requiresKeyMatterAccess: true`, remove `allowedUsernames`, and apply to both `/key-matters` and `/key-matters-meeting`.

Change guard to `router.beforeEach(async to => { ... })`. After token/login checks:

```ts
if (to.meta.requiresKeyMatterAccess) {
  const access = await api.getKeyMatterAccess().catch(() => null)
  if (!access?.canAccess) return '/'
}
```

The guard must not call `/api/key-matters` before allowing the route. A 401 remains handled by the API client; a 403/failed access result redirects to `/`.

- [ ] **Step 6: Update MainLayout menu**

Add `keyMatterAccess` to `visibleNavItems` filtering. The `/key-matters` item is visible iff `authStore.keyMatterAccess?.canAccess === true`. On mount call `await authStore.loadKeyMatterAccess(true)` alongside existing badge load; refresh after route access failures.

- [ ] **Step 7: Run frontend navigation tests**

```bash
cd frontend
npm run build
npx playwright test tests/key-matter-participant-access.spec.ts tests/navigation-permissions.spec.ts
```

Expected: new owner/participant/unrelated tests and existing navigation permission tests pass.

- [ ] **Step 8: Commit frontend access layer**

```bash
git add frontend/src/utils/api.ts frontend/src/stores/auth.ts frontend/src/router/index.ts \
  frontend/src/layouts/MainLayout.vue frontend/tests/navigation-permissions.spec.ts \
  frontend/tests/key-matter-participant-access.spec.ts
git commit -m "feat(key-matters): add dynamic access capability"
```

---

## Task 4: Key Matters Participant Form, Display and Owner-Only Feedback Controls

**Files:**
- Modify: `frontend/src/views/KeyMattersView.vue`
- Modify: `frontend/tests/key-matter-participant-access.spec.ts`
- Modify: `frontend/tests/key-matters.spec.ts`

- [ ] **Step 1: Add RED UI tests for participant display and controls**

Extend mocked matter fixtures with:

```ts
participants: [
  { userId: 7, username: 'shijiale', realName: '石家乐' },
  { userId: 16, username: 'yufeng', realName: '于峰' }
]
```

Add tests:

```ts
test('管理员可以维护参与人且负责人不可移除', async ({ page }) => {
  // access canManageAll; open edit; select participant; submit participantIds includes owner
})

test('负责人只能反馈本人事项', async ({ page }) => {
  // access canFeedbackOwn, current user id 7, fixture matters owner 7 and 16;
  // own row has update button, other row does not; detail history actions match.
})

test('普通参与人只能查看', async ({ page }) => {
  // access canAccess true/canFeedbackOwn false;
  // participants visible; no update/edit/delete/weekly actions anywhere.
})
```

Run them before production changes; expected RED because current form has no participants and all fixed access users see all controls.

- [ ] **Step 2: Add access-derived UI helpers**

In `KeyMattersView.vue`, read `authStore` and add:

```ts
const currentUserId = computed(() => authStore.user?.id)
const keyMatterAccess = computed(() => authStore.keyMatterAccess)
const canManageAll = computed(() => keyMatterAccess.value?.canManageAll === true)
function canFeedbackMatter(matter: BuKeyMatter) {
  return canManageAll.value
    || (keyMatterAccess.value?.canFeedbackOwn === true
      && matter.ownerId === currentUserId.value)
}
```

On page setup/load, force `await authStore.loadKeyMatterAccess(true)` before loading matters. On any API 403 from matter operations, reload access and show `ElMessage.error` with the server message; if `canAccess` becomes false, route to `/`.

- [ ] **Step 3: Add participants to matter form state**

Extend `MatterFormState`:

```ts
participantIds: number[]
```

Update `emptyMatterForm()` to return `participantIds: []`. `openEdit` maps `matter.participants?.map(item => item.userId) ?? [matter.ownerId]`. `saveMatter` sends `participantIds` and automatically ensures the current `ownerId` is present before calling `api.createKeyMatter`/`api.updateKeyMatter`.

When the owner select changes:

```ts
function handleMatterOwnerChange(ownerId: number | undefined) {
  if (ownerId === undefined) return
  if (!matterForm.participantIds.includes(ownerId)) {
    matterForm.participantIds.push(ownerId)
  }
}
```

- [ ] **Step 4: Add administrator-only participant multi-select**

In the matter drawer after owner field, add:

```vue
<el-form-item v-if="canManageAll" label="参与人" prop="participantIds">
  <el-select
    v-model="matterForm.participantIds"
    multiple
    filterable
    collapse-tags
    placeholder="选择参与人"
    aria-label="参与人"
  >
    <el-option
      v-for="user in users"
      :key="user.id"
      :label="user.realName || user.username"
      :value="user.id"
    />
  </el-select>
  <small class="field-help">负责人会自动加入参与人，参与人可查看全部大事儿。</small>
</el-form-item>
```

The owner must be added automatically and cannot be removed by a save payload; backend remains authoritative.

- [ ] **Step 5: Display participants in list and detail**

Add a compact list column showing first 2 participant names and `+N` for the remainder, with the owner marked by a small “负责人” label. Add a detail facts/section showing all participant names and owner marker. Use existing Element Plus tags/avatar styles; no global UI changes.

- [ ] **Step 6: Gate all operation surfaces**

Apply `canManageAll` to new/edit/delete matter controls and drawer footer. Apply `canFeedbackMatter(matter)` to:

- register row update button;
- detail weekly update button;
- history weekly edit/delete buttons;
- regular meeting `立即更新`/`更新周报` controls;
- presentation edit status controls, sliders, editor, draft/save footer;
- `openWeekly` guard before opening a new or existing update.

Read-only participants must still see all matter data, current reports, participants, and meeting grouping. They must not see any feedback controls. Completed-matter “无需更新” behavior from the previous feature remains unchanged.

- [ ] **Step 7: Add 403 owner-change recovery**

In `saveWeeklyUpdate`, `confirmDeleteWeekly`, and presentation save, when API error status/message indicates `仅事项负责人可反馈周进度`:

1. show server message;
2. close or exit edit mode;
3. `await authStore.loadKeyMatterAccess(true)`;
4. reload the current register/meeting data;
5. keep the matter visible as read-only.

Extend `ApiService` errors with `status?: number` if needed so frontend distinguishes 403 from generic 400 without parsing only message text.

- [ ] **Step 8: Run focused UI tests and build**

```bash
cd frontend
npm run build
npx playwright test tests/key-matter-participant-access.spec.ts tests/key-matters.spec.ts
```

Expected: all existing completed-week tests and new participant/owner tests pass.

- [ ] **Step 9: Commit UI implementation**

```bash
git add frontend/src/views/KeyMattersView.vue frontend/tests/key-matter-participant-access.spec.ts \
  frontend/tests/key-matters.spec.ts
git commit -m "feat(key-matters): add participants and owner feedback controls"
```

---

## Task 5: Contract Sync, Full Regression and Deployment

**Files:**
- Modify: `.trellis/spec/backend/key-matter-management-contract.md`
- Modify: `.trellis/spec/frontend/key-matter-management-ui.md`
- Modify: `frontend/tests/navigation-permissions.spec.ts` if existing expectations need access mock updates.
- Build: `backend/target/management-1.0.0.jar`, `frontend/dist/`

- [ ] **Step 1: Update backend contract**

Add executable entries for:

```markdown
GET /api/key-matters/access -> KeyMatterAccessView
GET/list/detail/meeting -> view/manage plus participant relationship
POST/PUT/DELETE matter -> manage plus admin username
PUT/DELETE weekly -> feedback/manage plus current owner after row lock
```

Document exact error matrix:

```markdown
- no relationship list/detail/meeting: 403 `无权访问大事儿`
- participant feedback: 403 `仅事项负责人可反馈周进度`
- owner-change race: 403 after lock with the same message
- inactive participant: 400 `参与人不存在或已停用`
- missing owner in participantIds: backend adds owner
```

- [ ] **Step 2: Update frontend contract**

Document dynamic access loading, menu/route behavior, participant display, administrator form rules, owner-only feedback controls, 403 refresh, and the E2E test matrix.

- [ ] **Step 3: Run full backend regression**

```bash
cd backend
mvn test
```

Expected: `BUILD SUCCESS`, including Flyway/migration integration checks, access tests, participant tests, lock tests, and all previous 157+ tests.

- [ ] **Step 4: Run full frontend regression**

```bash
cd frontend
npm run build
npx playwright test tests/key-matters.spec.ts tests/key-matter-participant-access.spec.ts \
  tests/navigation-permissions.spec.ts tests/responsive-list-layouts.spec.ts
```

Expected: all tests pass; no horizontal-overflow regression.

- [ ] **Step 5: Run migration validation against a clean MySQL schema**

Use the existing Docker MySQL service or test profile and verify:

```sql
SELECT COUNT(*) FROM bu_key_matter;
SELECT COUNT(DISTINCT key_matter_id) FROM bu_key_matter_participant;
SELECT COUNT(*)
FROM bu_key_matter matter
LEFT JOIN bu_key_matter_participant participant
  ON participant.key_matter_id = matter.id
 AND participant.user_id = matter.owner_id
WHERE participant.id IS NULL;
```

Expected: participant distinct matter count covers every existing matter and missing-owner count is 0.

- [ ] **Step 6: Build release artifacts**

```bash
cd backend
mvn clean package -DskipTests
cd ../frontend
npm run build
```

Expected: jar and dist produced successfully.

- [ ] **Step 7: Back up and deploy**

```bash
TS=$(date +%Y%m%d-%H%M%S)
ssh 241 "set -e; BACKUP=/home/openclaw/deploy-backups/superwork-key-matter-participants-$TS; mkdir -p \"\$BACKUP\"; cp /home/openclaw/superwork-claude-sp/backend/target/management-1.0.0.jar \"\$BACKUP/management-1.0.0.jar\"; cp -a /home/openclaw/superwork-claude-sp/frontend/dist \"\$BACKUP/frontend-dist\"; cp /home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT \"\$BACKUP/DEPLOYED_COMMIT\""
rsync -az backend/target/management-1.0.0.jar 241:/home/openclaw/superwork-claude-sp/backend/target/management-1.0.0.jar
rsync -az --delete frontend/dist/ 241:/home/openclaw/superwork-claude-sp/frontend/dist/
ssh 241 'cd /home/openclaw/superwork-claude-sp/docker && docker compose -f docker-compose.241.yml build backend frontend && docker compose -f docker-compose.241.yml up -d backend frontend'
```

Do not restart MySQL/Redis/MinIO unless migration health requires it. Wait for:

```bash
ssh 241 'curl -fsS http://localhost:18081/actuator/health'
```

Expected: `{"status":"UP"}`.

- [ ] **Step 8: Production permission smoke test**

Use four accounts and existing matter IDs:

```text
admin/yufeng: access canManageAll=true; create/edit/delete and any feedback succeed.
current owner: access canAccess=true/canFeedbackOwn=true; list all; feedback own succeeds; other matter 403.
ordinary participant: access canAccess=true/canFeedbackOwn=false; list all; weekly write 403.
unrelated user: access all false; menu/route hidden; list/detail/meeting 403.
```

After changing owner in admin UI, repeat owner write checks: old owner 403, new owner succeeds.

- [ ] **Step 9: Update deployment marker and record rollback**

```bash
COMMIT=$(git rev-parse --short HEAD)
ssh 241 "printf '$COMMIT' > /home/openclaw/superwork-claude-sp/DEPLOYED_COMMIT"
```

Record the backup path, migration validation counts, container health, smoke results, and rollback command that restores the backed-up jar/dist and rebuilds only backend/frontend.

- [ ] **Step 10: Final verification and commit contract changes**

```bash
git diff --check
git status -sb
git log --oneline -8
```

Commit contract changes before deployment if not already committed:

```bash
git add .trellis/spec/backend/key-matter-management-contract.md \
  .trellis/spec/frontend/key-matter-management-ui.md
git commit -m "docs(key-matters): document participant access contract"
```

---

## Plan Self-Review

### Spec coverage

- Independent participant relation table and owner backfill: Task 1.
- No investment percentage: explicitly excluded in header and Task 1.
- Admin/owner/participant/unrelated matrix: Tasks 2–4.
- Access capability endpoint: Tasks 2–3.
- Owner-only weekly create/edit/delete and row-lock owner transfer: Task 2.
- Admin-only matter CRUD and participant maintenance: Tasks 2 and 4.
- Participants can view all but cannot feedback: Tasks 2 and 4.
- Owner auto-inclusion and participant validation: Tasks 1–2 and 4.
- Participant display: Task 4.
- 403 error semantics: Tasks 1–2 and 4.
- Migration, tests, deployment, rollback: Task 5.

### Consistency checks

- Backend names are consistent: `BuKeyMatterParticipant`, `BuKeyMatterParticipantMapper`, `BuKeyMatterAccessService`, `BuKeyMatterAccessView`, `ForbiddenOperationException`, `participantIds`.
- Frontend names are consistent: `KeyMatterAccess`, `keyMatterAccess`, `loadKeyMatterAccess`, `canFeedbackMatter`.
- Permission codes are consistent across migration, controller annotations, access service and tests: `bu:key-matter:view`, `bu:key-matter:feedback`, `bu:key-matter:manage`.
- Exact error strings are consistent: `无权访问大事儿`, `仅事项负责人可反馈周进度`, `参与人不存在或已停用`.
- Existing completed-matter rule `已完成事项无需新增周进展` remains unchanged and is explicitly preserved.

### Scope exclusions

- No投入比例 or participation history.
- No changes to requirements, tasks, defects, email, dashboard or global layout beyond the existing key-matter menu visibility.
- No replacement of JWT claims; access is resolved from current database relationships.
- No destructive database rollback in normal release.
