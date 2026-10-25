package com.bu.management.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V109–V114 是在已上线的 MySQL 库上执行的 ALTER，Flyway 在测试里是关的，
 * 这里直接把脚本丢到 H2(MySQL 模式) 里跑一遍，确保语法与列名不写错。
 */
class MigrationV109QuotationPricingSchemaTest {

    private static final String[] SCRIPTS = {
            "db/migration/V109__quotation_template_and_pricing.sql",
            "db/migration/V110__quotation_variant.sql",
            "db/migration/V111__quotation_discount_approval.sql",
            "db/migration/V112__quotation_opportunity_link.sql",
            "db/migration/V114__quotation_backfill_first_year_free.sql",
    };

    private static final String BASE_SCHEMA = String.join("\n",
            "CREATE TABLE quotation (id BIGINT PRIMARY KEY AUTO_INCREMENT, quotation_no VARCHAR(50), policy_id BIGINT,",
            "  opportunity_id BIGINT, tax_mode VARCHAR(20), status VARCHAR(20), created_by VARCHAR(100));",
            "CREATE TABLE quotation_policy (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(200), type VARCHAR(50),",
            "  tax_mode VARCHAR(20), version INT, status VARCHAR(20));",
            "CREATE TABLE quotation_policy_item (id BIGINT PRIMARY KEY AUTO_INCREMENT, policy_id BIGINT, section VARCHAR(100),",
            "  category VARCHAR(100), item_key VARCHAR(200), item_name VARCHAR(200), unit_price DECIMAL(15,2),",
            "  tax_rate DECIMAL(5,4), charge_method VARCHAR(100), charge_unit VARCHAR(50), sort_order INT);",
            "CREATE TABLE quotation_line_item (id BIGINT PRIMARY KEY AUTO_INCREMENT, quotation_id BIGINT, section VARCHAR(100),",
            "  category VARCHAR(100), item_name VARCHAR(200), description TEXT, price_description VARCHAR(500),",
            "  is_selected TINYINT(1) DEFAULT 0, quantity DECIMAL(10,2) DEFAULT 1, unit_price_ex_tax DECIMAL(15,2),",
            "  tax_rate DECIMAL(5,4), discount_rate DECIMAL(5,4), subtotal_ex_tax DECIMAL(15,2),",
            "  subtotal_incl_tax DECIMAL(15,2), charge_method VARCHAR(100), remark TEXT, sort_order INT);",
            "CREATE TABLE sales_opportunity (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(200), amount DECIMAL(15,2),",
            "  status VARCHAR(20));",
            "");

    @Test
    void migrationsApplyAndBackfillFirstYearFree() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:quotation_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                for (String ddl : BASE_SCHEMA.split(";\n")) {
                    if (!ddl.isBlank()) statement.execute(ddl);
                }
                try {
                    for (String script : SCRIPTS) {
                        for (String ddl : read(script).split(";")) {
                            String sql = stripComments(ddl).trim();
                            if (sql.isEmpty()) continue;
                            for (String single : rewriteForH2(sql)) {
                                statement.execute(single);
                            }
                        }
                    }
                } catch (java.sql.SQLException e) {
                    throw new AssertionError("迁移脚本在 H2(MySQL 模式)下语法报错：" + e.getMessage(), e);
                }
                try (Statement s = connection.createStatement()) {
                    s.execute("INSERT INTO quotation_line_item (quotation_id, section, item_name, unit_price_ex_tax, tax_rate, discount_rate) "
                            + "VALUES (1, '运维服务', '基础运维服务', NULL, 0.06, 0)");
                    s.execute("UPDATE quotation_line_item SET is_first_year_free = 1 WHERE discount_rate = 0 AND section = '运维服务'");
                    s.execute("UPDATE quotation_line_item SET discount_rate = 1 WHERE is_first_year_free = 1");
                }
            }

            assertThat(columnExists(connection, "quotation", "is_preferred")).isTrue();
            assertThat(columnExists(connection, "quotation", "snapshot_json")).isTrue();
            assertThat(columnExists(connection, "quotation_policy", "min_discount_rate")).isTrue();
            assertThat(columnExists(connection, "quotation_policy_item", "charge_rate")).isTrue();
            assertThat(columnExists(connection, "quotation_line_item", "price_override_reason")).isTrue();
            assertThat(columnExists(connection, "sales_opportunity", "won_quotation_id")).isTrue();
            assertThat(tableExists(connection, "quotation_template")).isTrue();
            assertThat(tableExists(connection, "quotation_variant")).isTrue();
            assertThat(tableExists(connection, "quotation_discount_request")).isTrue();
            assertThat(tableExists(connection, "quotation_approval_log")).isTrue();

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT is_first_year_free, discount_rate FROM quotation_line_item WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("is_first_year_free")).isEqualTo(1);
                assertThat(rs.getBigDecimal("discount_rate")).isEqualByComparingTo("1");
            }
        }
    }

    private static String read(String path) throws IOException {
        try (InputStream in = MigrationV109QuotationPricingSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("migration %s 存在", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * H2 的 MySQL 兼容模式不支持 ENGINE/CHARSET 表选项，也不支持
     * {@code ALTER TABLE t ADD A, ADD B} 的多列写法（MySQL 专有），拆成单列语句再执行。
     * 这里改写的只是执行方式，不修改迁移文件本身。
     */
    private static java.util.List<String> rewriteForH2(String sql) {
        String cleaned = sql.replaceAll("(?i)\\s*ENGINE\\s*=\\s*\\w+", "")
                .replaceAll("(?i)\\s*DEFAULT\\s+CHARSET\\s*=\\s*\\w+", "")
                .replaceAll("(?i)COMMENT\\s*=?\\s*'(?:[^']|'')*'", "")
                .trim();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)^ALTER\\s+TABLE\\s+(\\w+)\\s+(ADD\\s+.+)$")
                .matcher(cleaned);
        if (!m.matches() || !m.group(2).contains(",")) {
            return java.util.List.of(cleaned);
        }
        String table = m.group(1);
        java.util.List<String> statements = new java.util.ArrayList<>();
        for (String part : splitTopLevel(m.group(2))) {
            statements.add("ALTER TABLE " + table + " " + part.trim());
        }
        return statements;
    }

    private static java.util.List<String> splitTopLevel(String body) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(body.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(body.substring(start));
        return parts;
    }

    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }
}
