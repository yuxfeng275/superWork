package com.bu.management.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V118 迁移脚本健壮性检查：AI 制品与评审队列。
 * 证据列、DRAFT 默认状态与两条索引是评审队列能跑起来的地基，
 * 字段缺失会静默降级成「谁都看不到待评审制品」，所以用文本断言兜住。
 */
class MigrationV118SanityTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V118__ai_artifact.sql");

    @Test
    @DisplayName("V118 迁移文件存在且非空")
    void migrationFileExists() throws IOException {
        assertThat(Files.exists(MIGRATION)).isTrue();
        assertThat(Files.readString(MIGRATION)).isNotBlank();
    }

    @Test
    @DisplayName("ai_artifact 带证据列：无证据不允许进评审")
    void artifactCarriesEvidence() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ai_artifact");
        assertThat(sql).contains("evidence");
        assertThat(sql).contains("project_fact.id");
        assertThat(sql).contains("owner_user_id");
        assertThat(sql).contains("reject_reason");
    }

    @Test
    @DisplayName("ai_artifact 状态默认 DRAFT，且按项目+状态、任务号可查")
    void artifactStatusAndIndexes() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("status");
        assertThat(sql).contains("DEFAULT 'DRAFT'");
        assertThat(sql).contains("KEY idx_artifact_project (project_id, status)");
        assertThat(sql).contains("KEY idx_artifact_task (task_id)");
    }
}
