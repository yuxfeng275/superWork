package com.bu.management.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeAll;

/**
 * 工时分析服务单元测试：身份解析（显式映射/姓名匹配/同名歧义）与月度报表。
 */
@ExtendWith(MockitoExtension.class)
class WorktimeAnalyticsServiceTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                com.bu.management.entity.RevenueWorklogEntry.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                com.bu.management.entity.User.class);
    }

    @Mock
    private com.bu.management.mapper.RevenueWorklogEntryMapper worklogMapper;
    @Mock
    private com.bu.management.mapper.UserMapper userMapper;
    @Mock
    private AiConnectorIdentityService identityService;

    private WorktimeAnalyticsService service;

    private com.bu.management.entity.User user(Long id, String realName) {
        com.bu.management.entity.User user = new com.bu.management.entity.User();
        user.setId(id);
        user.setUsername("u" + id);
        user.setRealName(realName);
        return user;
    }

    private com.bu.management.entity.RevenueWorklogEntry entry(String month, String no, String name,
            String project, String line, BigDecimal hours) {
        com.bu.management.entity.RevenueWorklogEntry entry = new com.bu.management.entity.RevenueWorklogEntry();
        entry.setYearMonth(month);
        entry.setEmployeeNo(no);
        entry.setEmployeeName(name);
        entry.setProjectNameRaw(project);
        entry.setBusinessLineName(line);
        entry.setWorkType("project");
        entry.setHours(hours);
        return entry;
    }

    @Test
    @DisplayName("resolveIdentity：显式映射优先")
    void identityPrefersExplicitMapping() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME))
                .thenReturn("00903");

        var identity = service.resolveIdentity(7L);

        assertThat(identity).isNotNull();
        assertThat(identity.employeeNo()).isEqualTo("00903");
        assertThat(identity.source()).isEqualTo("IDENTITY_MAP");
    }

    @Test
    @DisplayName("resolveIdentity：无映射时按姓名唯一匹配")
    void identityFallsBackToNameMatch() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME)).thenReturn(null);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "石家乐"));
        var row = new com.bu.management.entity.RevenueWorklogEntry();
        row.setEmployeeNo("00903");
        when(worklogMapper.selectList(any())).thenReturn(List.of(row));

        var identity = service.resolveIdentity(7L);

        assertThat(identity).isNotNull();
        assertThat(identity.employeeNo()).isEqualTo("00903");
        assertThat(identity.source()).isEqualTo("NAME_MATCH");
    }

    @Test
    @DisplayName("resolveIdentity：同名多工号（如两个刘洋）返回 null")
    void ambiguousNameReturnsNull() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME)).thenReturn(null);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "刘洋"));
        var row1 = new com.bu.management.entity.RevenueWorklogEntry();
        row1.setEmployeeNo("00873");
        var row2 = new com.bu.management.entity.RevenueWorklogEntry();
        row2.setEmployeeNo("00957");
        when(worklogMapper.selectList(any())).thenReturn(List.of(row1, row2));

        assertThat(service.resolveIdentity(7L)).isNull();
    }

    @Test
    @DisplayName("personalMonthly：聚合项目与业务线分布")
    void monthlyReportAggregates() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(identityService.resolve(7L, AiConnectorIdentityService.CONNECTOR_WORKTIME))
                .thenReturn("00903");
        when(worklogMapper.selectList(any())).thenReturn(List.of(
                entry("2026-08", "00903", "石家乐", "皇家宠物项目", "电商业务BU", new BigDecimal("1.5")),
                entry("2026-08", "00903", "石家乐", "皇家宠物项目", "电商业务BU", new BigDecimal("2.5")),
                entry("2026-08", "00903", "石家乐", "逢时项目", "电商业务BU", new BigDecimal("1.0"))));

        var report = service.personalMonthly(7L, "2026-08");

        assertThat(report).isNotNull();
        assertThat(report.totalHours()).isEqualByComparingTo("5.00");
        assertThat(report.projectHours()).hasSize(2);
        assertThat(report.projectHours().get("皇家宠物项目")).isEqualByComparingTo("4.00");
        assertThat(report.entryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("teamMonthly：按业务线汇总并统计人数")
    void teamReportAggregates() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(worklogMapper.selectList(any())).thenReturn(List.of(
                entry("2026-08", "00903", "石家乐", "皇家宠物项目", "电商业务BU", new BigDecimal("3.0")),
                entry("2026-08", "00504", "于峰", "澳优项目", "电商业务BU", new BigDecimal("2.0")),
                entry("2026-08", "00001", "王缓", "云鹿SaaS", "全渠道", new BigDecimal("1.0"))));

        var report = service.teamMonthly("2026-08");

        assertThat(report.totalHours()).isEqualByComparingTo("6.00");
        assertThat(report.lines()).hasSize(2);
        assertThat(report.lines().get(0).businessLine()).isEqualTo("电商业务BU");
        assertThat(report.lines().get(0).people()).isEqualTo(2);
    }

    @Test
    @DisplayName("missingReportUsers：找出该月无工时的用户（排除管理员）")
    void missingUsersDetectsGaps() {
        service = new WorktimeAnalyticsService(worklogMapper, userMapper, identityService);
        when(userMapper.selectList(any())).thenReturn(List.of(
                user(9L, "丛宁"), user(10L, "系统管理员"), user(12L, "小刘洋")));
        when(worklogMapper.selectCount(any()))
                .thenReturn(3L)     // 丛宁：有记录
                .thenReturn(0L);    // 小刘洋：无记录

        var missing = service.missingReportUsers("2026-08");

        assertThat(missing).containsExactly("小刘洋");
    }
}
