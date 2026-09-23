package com.bu.management.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V91 迁移脚本健壮性检查：「会议与日程」父菜单 + 两个子项归并 + 角色授权。
 */
class MigrationV91SanityTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V91__merge_meeting_schedule_menu.sql");

    @Test
    @DisplayName("V91 迁移文件存在且非空")
    void migrationFileExists() throws IOException {
        assertThat(Files.exists(MIGRATION)).isTrue();
        assertThat(Files.readString(MIGRATION)).isNotBlank();
    }

    @Test
    @DisplayName("V91 插入「会议与日程」父菜单并归并会议/日程为其子项")
    void migrationCreatesParentAndReparents() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("INSERT INTO sys_menu (parent_id, name, icon, path, component, sort_order, visible, status)");
        assertThat(sql).contains("'会议与日程'");
        assertThat(sql).contains("'/meeting-schedule'");

        // 两个子项归并（/meetings 在前，/schedule 在后）
        assertThat(sql).contains("WHERE path = '/meetings'");
        assertThat(sql).contains("WHERE path = '/schedule'");
        assertThat(sql).contains("sort_order = 1");
        assertThat(sql).contains("sort_order = 2");
    }

    @Test
    @DisplayName("V91 授权父菜单给五管理角色")
    void migrationGrantsParentToRoles() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("INSERT IGNORE INTO sys_role_menu (role_id, menu_id)");
        assertThat(sql).contains("'/meeting-schedule'");
        assertThat(sql).contains(
                "'DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN'");
    }
}
