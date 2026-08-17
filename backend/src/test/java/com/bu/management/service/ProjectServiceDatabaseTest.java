package com.bu.management.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class ProjectServiceDatabaseTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpSchema() {
        dropTables();

        jdbcTemplate.execute("""
                CREATE TABLE project (
                    id BIGINT PRIMARY KEY,
                    business_line_id BIGINT NOT NULL,
                    parent_id BIGINT,
                    level TINYINT NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    full_path VARCHAR(500),
                    code VARCHAR(50),
                    manager_id BIGINT,
                    status TINYINT,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP,
                    CONSTRAINT fk_project_parent FOREIGN KEY (parent_id) REFERENCES project(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE customer_contact (
                    id BIGINT PRIMARY KEY,
                    project_id BIGINT NOT NULL,
                    CONSTRAINT fk_contact_project FOREIGN KEY (project_id) REFERENCES project(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE requirement (
                    id BIGINT PRIMARY KEY,
                    project_id BIGINT,
                    customer_contact_id BIGINT,
                    CONSTRAINT fk_requirement_project FOREIGN KEY (project_id) REFERENCES project(id),
                    CONSTRAINT fk_requirement_contact FOREIGN KEY (customer_contact_id) REFERENCES customer_contact(id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE issue (
                    id BIGINT PRIMARY KEY,
                    project_id BIGINT,
                    CONSTRAINT fk_issue_project FOREIGN KEY (project_id) REFERENCES project(id)
                )
                """);

        jdbcTemplate.update("""
                INSERT INTO project (
                    id, business_line_id, parent_id, level, name, full_path, code, manager_id, status
                ) VALUES (5, 2, NULL, 1, 'SAAS平台', 'SAAS平台', 'SAAS', 2, 1)
                """);
        jdbcTemplate.update("INSERT INTO customer_contact (id, project_id) VALUES (51, 5)");
        jdbcTemplate.update(
                "INSERT INTO requirement (id, project_id, customer_contact_id) VALUES (61, 5, 51)");
        jdbcTemplate.update("INSERT INTO issue (id, project_id) VALUES (71, 5)");
    }

    @AfterEach
    void tearDownSchema() {
        dropTables();
    }

    @Test
    void deleteLeafProjectDetachesHistoricalRecordsAndRemovesProjectContacts() {
        projectService.delete(5L);

        assertEquals(0, count("SELECT COUNT(*) FROM project WHERE id = 5"));
        assertEquals(0, count("SELECT COUNT(*) FROM customer_contact WHERE id = 51"));
        assertEquals(1, count("SELECT COUNT(*) FROM requirement WHERE id = 61"));
        assertNull(value("SELECT project_id FROM requirement WHERE id = 61"));
        assertNull(value("SELECT customer_contact_id FROM requirement WHERE id = 61"));
        assertEquals(1, count("SELECT COUNT(*) FROM issue WHERE id = 71"));
        assertNull(value("SELECT project_id FROM issue WHERE id = 71"));
    }

    @Test
    void deleteLeafProjectWithoutHistoricalRecords() {
        jdbcTemplate.update("DELETE FROM requirement");
        jdbcTemplate.update("DELETE FROM issue");
        jdbcTemplate.update("DELETE FROM customer_contact");

        projectService.delete(5L);

        assertEquals(0, count("SELECT COUNT(*) FROM project WHERE id = 5"));
    }

    @Test
    void deleteParentProjectRejectsBeforeDetachingHistoricalRecords() {
        jdbcTemplate.update("""
                INSERT INTO project (
                    id, business_line_id, parent_id, level, name, full_path, code, manager_id, status
                ) VALUES (6, 2, 5, 2, '子项目', 'SAAS平台/子项目', 'SAAS-CHILD', 2, 1)
                """);

        RuntimeException error = assertThrows(RuntimeException.class, () -> projectService.delete(5L));

        assertEquals("存在子项目，无法删除", error.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM project WHERE id = 5"));
        assertEquals(5L, value("SELECT project_id FROM requirement WHERE id = 61"));
        assertEquals(51L, value("SELECT customer_contact_id FROM requirement WHERE id = 61"));
        assertEquals(5L, value("SELECT project_id FROM issue WHERE id = 71"));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private Long value(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private void dropTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS issue");
        jdbcTemplate.execute("DROP TABLE IF EXISTS requirement");
        jdbcTemplate.execute("DROP TABLE IF EXISTS customer_contact");
        jdbcTemplate.execute("DROP TABLE IF EXISTS project");
    }
}
