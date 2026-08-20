package com.bu.management.service;

import com.bu.management.dto.BuKeyMatterWeeklyUpdateRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.mapper.BuKeyMatterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class BuKeyMatterLockIntegrationTest {

    private static final long MATTER_ID = 11L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 3);

    @Autowired
    private BuKeyMatterService buKeyMatterService;

    @Autowired
    private BuKeyMatterMapper matterMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private BuKeyMatterAccessService accessService;

    private TransactionTemplate transactionTemplate;
    private ExecutorService executor;

    @BeforeEach
    void setUpSchema() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);
        dropTables();
        jdbcTemplate.execute("""
                CREATE TABLE bu_key_matter (
                    id BIGINT PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    description VARCHAR(500),
                    project_id BIGINT,
                    owner_id BIGINT NOT NULL,
                    priority VARCHAR(2) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    progress INT NOT NULL,
                    start_date DATE NOT NULL,
                    planned_completion_date DATE NOT NULL,
                    completed_at TIMESTAMP,
                    sort_order INT NOT NULL,
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE bu_key_matter_weekly_update (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    key_matter_id BIGINT NOT NULL,
                    week_start_date DATE NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    progress INT NOT NULL,
                    progress_summary VARCHAR(1000) NOT NULL,
                    issues VARCHAR(1000),
                    next_week_plan VARCHAR(1000),
                    support_needed VARCHAR(1000),
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP,
                    UNIQUE (key_matter_id, week_start_date)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO bu_key_matter (
                    id, title, owner_id, priority, status, progress,
                    start_date, planned_completion_date, sort_order, created_by,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, MATTER_ID, "事项11", 7L, "P1", "推进中", 40,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 28), 0, 16L,
                LocalDateTime.of(2026, 8, 1, 9, 0), LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    @AfterEach
    void tearDownSchema() {
        shutdownExecutor();
        dropTables();
    }

    @Test
    void completedUpdateHoldsRowLockUntilCommitAndBlocksWeeklyUpsert() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Throwable> transactionA = executor.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    BuKeyMatter matter = matterMapper.selectByIdForUpdate(MATTER_ID);
                    matter.setStatus("已完成");
                    matter.setProgress(100);
                    matter.setCompletedAt(LocalDateTime.of(2026, 8, 2, 10, 0));
                    matter.setUpdatedAt(LocalDateTime.of(2026, 8, 2, 10, 0));
                    matterMapper.updateById(matter);
                    lockHeld.countDown();
                    await(release);
                });
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("transaction A must acquire the row lock before proceeding")
                .isTrue();

        CountDownLatch started = new CountDownLatch(1);
        Future<Throwable> transactionB = executor.submit(() -> {
            started.countDown();
            try {
                buKeyMatterService.upsertWeeklyUpdate(
                        MATTER_ID, MONDAY, weeklyRequest("推进中", 90, "推进中进展"), 16L, "yufeng");
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("transaction B must start")
                .isTrue();

        assertBlocked(transactionB, 2000);
        assertThat(countWeeklyUpdates())
                .as("no weekly row may be inserted while the row lock is held")
                .isZero();

        release.countDown();

        Throwable weeklyError = transactionB.get(10, TimeUnit.SECONDS);
        assertThat(weeklyError)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("已完成事项无需新增周进展");

        assertThat(transactionA.get(10, TimeUnit.SECONDS))
                .as("transaction A must commit cleanly after being released")
                .isNull();

        assertThat(countWeeklyUpdates()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bu_key_matter WHERE id = ?", String.class, MATTER_ID))
                .isEqualTo("已完成");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT progress FROM bu_key_matter WHERE id = ?", Integer.class, MATTER_ID))
                .isEqualTo(100);
        LocalDateTime committedAt = jdbcTemplate.queryForObject(
                "SELECT completed_at FROM bu_key_matter WHERE id = ?",
                (rs, rowNum) -> rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
                MATTER_ID);
        assertThat(committedAt).isEqualTo(LocalDateTime.of(2026, 8, 2, 10, 0));
    }

    @Test
    void ownerChangeRaceRejectsOldOwnerAfterCommitAndAllowsNewOwner() throws Exception {
        doThrow(new ForbiddenOperationException("仅事项负责人可反馈周进度"))
                .when(accessService)
                .requireFeedback(any(BuKeyMatter.class), eq(7L), anyString());

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Throwable> ownerChange = executor.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    BuKeyMatter matter = matterMapper.selectByIdForUpdate(MATTER_ID);
                    matter.setOwnerId(21L);
                    matter.setUpdatedAt(LocalDateTime.of(2026, 8, 2, 10, 0));
                    matterMapper.updateById(matter);
                    lockHeld.countDown();
                    await(release);
                });
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        assertThat(lockHeld.await(10, TimeUnit.SECONDS))
                .as("owner change transaction must acquire the row lock before proceeding")
                .isTrue();

        CountDownLatch started = new CountDownLatch(1);
        Future<Throwable> oldOwnerWrite = executor.submit(() -> {
            started.countDown();
            try {
                buKeyMatterService.upsertWeeklyUpdate(
                        MATTER_ID, MONDAY, weeklyRequest("推进中", 60, "旧负责人进展"), 7L, "shijiale");
                return null;
            } catch (Throwable t) {
                return t;
            }
        });

        assertThat(started.await(10, TimeUnit.SECONDS))
                .as("old owner weekly write must start")
                .isTrue();

        assertBlocked(oldOwnerWrite, 2000);
        assertThat(countWeeklyUpdates())
                .as("no weekly row may be written before the owner change commits")
                .isZero();

        release.countDown();

        Throwable oldOwnerError = oldOwnerWrite.get(10, TimeUnit.SECONDS);
        assertThat(oldOwnerError)
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅事项负责人可反馈周进度");

        assertThat(ownerChange.get(10, TimeUnit.SECONDS))
                .as("owner change transaction must commit cleanly")
                .isNull();

        assertThat(countWeeklyUpdates()).isZero();

        buKeyMatterService.upsertWeeklyUpdate(
                MATTER_ID, MONDAY, weeklyRequest("推进中", 60, "新负责人进展"), 21L, "newowner");
        assertThat(countWeeklyUpdates()).isEqualTo(1);
    }

    private BuKeyMatterWeeklyUpdateRequest weeklyRequest(
            String status, int progress, String summary) {
        BuKeyMatterWeeklyUpdateRequest request = new BuKeyMatterWeeklyUpdateRequest();
        request.setStatus(status);
        request.setProgress(progress);
        request.setProgressSummary(summary);
        request.setIssues("资源排期存在冲突");
        request.setNextWeekPlan("完成灰度验证");
        request.setSupportNeeded("确认上线窗口");
        return request;
    }

    private void assertBlocked(Future<?> future, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                fail("expected the weekly upsert to stay blocked while the row lock is held");
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private int countWeeklyUpdates() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bu_key_matter_weekly_update", Integer.class);
        return count == null ? 0 : count;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the test to release the row lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the test to release the row lock", e);
        }
    }

    private void shutdownExecutor() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void dropTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS bu_key_matter_weekly_update");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bu_key_matter");
    }
}
