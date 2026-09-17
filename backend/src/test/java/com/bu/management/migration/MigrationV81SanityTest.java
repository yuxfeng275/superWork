package com.bu.management.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V81 迁移脚本健壮性检查：会议三表、无需求任务 ALTER、meeting 配置组、菜单/权限/角色授权齐备。
 */
class MigrationV81SanityTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V81__add_meeting_module.sql");

    @Test
    @DisplayName("V81 迁移文件存在且非空")
    void migrationFileExists() throws IOException {
        assertThat(Files.exists(MIGRATION)).isTrue();
        String sql = Files.readString(MIGRATION);
        assertThat(sql).isNotBlank();
    }

    @Test
    @DisplayName("V81 包含会议三表 DDL 与外键/唯一键")
    void migrationContainsMeetingTables() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS meeting");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS meeting_speaker");
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS meeting_todo");

        // 主表：原始转写不可变 + 校正稿 + 摘要
        assertThat(sql).contains("transcript_json MEDIUMTEXT NULL COMMENT '原始转写（不可变）'");
        assertThat(sql).contains("corrected_transcript_json MEDIUMTEXT NULL COMMENT '人工校正稿'");
        assertThat(sql).contains("summary_json MEDIUMTEXT NULL");

        // 说话人唯一键与两处级联外键
        assertThat(sql).contains("uk_meeting_speaker (meeting_id, speaker_label)");
        assertThat(sql).contains("fk_meeting_speaker_meeting");
        assertThat(sql).contains("fk_meeting_todo_meeting");
    }

    @Test
    @DisplayName("V81 放开 task.requirement_id 为 NULL（无需求任务）")
    void migrationAllowsTaskWithoutRequirement() throws IOException {
        String sql = Files.readString(MIGRATION);
        assertThat(sql).contains("ALTER TABLE task MODIFY COLUMN requirement_id BIGINT NULL");
    }

    @Test
    @DisplayName("V81 包含 meeting 配置组 6 项，token 声明为敏感 PASSWORD")
    void migrationContainsMeetingConfigGroup() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("INSERT INTO system_config_item");
        for (String key : List.of(
                "meeting.enabled", "meeting.audio.max-size-mb", "meeting.asr.base-url",
                "meeting.asr.token", "meeting.asr.timeout-seconds", "meeting.asr.hotwords")) {
            assertThat(sql).contains("'" + key + "'");
        }
        // token 行：PASSWORD + NULL 默认值 + is_sensitive=1 + is_required=0 + sort_order=40
        assertThat(sql).contains("'PASSWORD', NULL, 1, 0, 40, 1");
        // 首行带 AS 别名、UNION 分支为位置参数（最后一行 hotwords sort_order=60）
        assertThat(sql).contains("'BOOLEAN' AS value_type");
        assertThat(sql).contains("'STRING', NULL, 0, 0, 60, 1");
    }

    @Test
    @DisplayName("V81 注册 /meetings 菜单、权限并授权五管理角色")
    void migrationContainsMenuAndPermissions() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("'/meetings'");
        assertThat(sql).contains("'Timer'");
        assertThat(sql).contains("'meeting:view'");
        assertThat(sql).contains("'meeting:manage'");
        assertThat(sql).contains("INSERT IGNORE INTO sys_role_menu");
        assertThat(sql).contains("INSERT IGNORE INTO sys_role_permission");
        assertThat(sql).contains(
                "'DIRECTOR', 'DEPUTY_DIRECTOR', 'BUSINESS_OWNER', 'EFFECTIVENESS_OWNER', 'BU_ADMIN'");
    }
}
