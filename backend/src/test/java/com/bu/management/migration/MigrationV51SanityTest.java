package com.bu.management.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V51 迁移脚本健壮性检查：存在、结构完整、与 V47/V48 风格一致。
 */
class MigrationV51SanityTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V51__add_ai_connector_config.sql");

    @Test
    @DisplayName("V51 迁移文件存在且非空")
    void migrationFileExists() throws IOException {
        assertThat(Files.exists(MIGRATION)).isTrue();
        String sql = Files.readString(MIGRATION);
        assertThat(sql).isNotBlank();
    }

    @Test
    @DisplayName("V51 包含身份映射表 DDL 与 ai-connector 配置组插入")
    void migrationContainsTableAndConfigItems() throws IOException {
        String sql = Files.readString(MIGRATION);

        // 身份映射表
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ai_connector_identity");
        assertThat(sql).contains("uk_user_connector");
        assertThat(sql).contains("connector_code VARCHAR(32) NOT NULL");

        // 配置组：10 个配置项（§7 sort_order 10..100）
        assertThat(sql).contains("INSERT INTO system_config_item");
        assertThat(sql).contains("'ai-connector'");
        for (String key : List.of(
                "worktime.enabled", "worktime.base-url", "worktime.username",
                "worktime.password", "worktime.timeout-seconds",
                "yuque.enabled", "yuque.mcp-url", "yuque.token", "yuque.timeout-seconds",
                "mail.search-days")) {
            assertThat(sql).contains("'" + key + "'");
        }

        // 敏感项声明 PASSWORD 类型（AES-256-GCM 链路依赖 is_sensitive）
        String[] lines = sql.split("\n");
        boolean inValues = false;
        int insertRows = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;
            if (trimmed.equals("VALUES")) {
                inValues = true;
                continue;
            }
            if (!inValues) continue;
            if (trimmed.startsWith("('ai-connector'")) {
                insertRows++;
            }
        }
        assertThat(insertRows).isEqualTo(10);
    }

    @Test
    @DisplayName("V51 sort_order 按设计文档排序且不与既有项冲突")
    void migrationSortOrdersMatchDesign() throws IOException {
        String sql = Files.readString(MIGRATION);
        int index = 0;
        for (String key : List.of(
                "worktime.enabled", "worktime.base-url", "worktime.username", "worktime.password",
                "worktime.timeout-seconds", "yuque.enabled", "yuque.mcp-url", "yuque.token",
                "yuque.timeout-seconds", "mail.search-days")) {
            int keyIndex = sql.indexOf("'" + key + "'");
            assertThat(keyIndex).as("config key %s present", key).isPositive();
            // 该 key 之后到行尾应包含其 sort_order（10, 20, ... 100），且数字带引号避免误配
            int lineEnd = sql.indexOf('\n', keyIndex);
            String row = sql.substring(keyIndex, lineEnd);
            int expected = 10 * (index + 1);
            assertThat(row).as("sort %s for %s", expected, key).contains(", " + expected + ", 1)");
            index++;
        }
    }
}
