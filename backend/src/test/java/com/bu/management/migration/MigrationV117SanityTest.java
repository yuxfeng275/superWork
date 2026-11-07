package com.bu.management.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V117 迁移脚本健壮性检查：项目事实层 / 项目仓库绑定 / 每人晨报三张表。
 * 这三张表是「夜间自动干活 + 次日分发」的地基，字段缺失会静默降级成空结果，所以用文本断言兜住。
 */
class MigrationV117SanityTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V117__project_fact_and_brief.sql");

    @Test
    @DisplayName("V117 迁移文件存在且非空")
    void migrationFileExists() throws IOException {
        assertThat(Files.exists(MIGRATION)).isTrue();
        assertThat(Files.readString(MIGRATION)).isNotBlank();
    }

    @Test
    @DisplayName("project_fact 带出处、可见性与幂等键")
    void projectFactHasEvidenceAndDedupeColumns() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS project_fact");
        assertThat(sql).contains("source_type");
        assertThat(sql).contains("source_ref");
        assertThat(sql).contains("visibility");
        assertThat(sql).contains("dedupe_hash");
        assertThat(sql).contains("UNIQUE KEY uk_fact_hash (dedupe_hash)");
        // 项目维度的时间线查询
        assertThat(sql).contains("KEY idx_fact_project (project_id, happened_at)");
    }

    @Test
    @DisplayName("project_git_repo 记录 HTTPS 地址、默认分支与 agent")
    void projectGitRepoTracksBinding() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS project_git_repo");
        assertThat(sql).contains("repo_url");
        assertThat(sql).contains("default_branch");
        assertThat(sql).contains("work_dir");
        assertThat(sql).contains("agent");
    }

    @Test
    @DisplayName("ai_brief 一人一天一条")
    void aiBriefIsUniquePerUserPerDay() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ai_brief");
        assertThat(sql).contains("UNIQUE KEY uk_brief_user_day (user_id, brief_date, project_id)");
        assertThat(sql).contains("delivered_at");
    }
}
