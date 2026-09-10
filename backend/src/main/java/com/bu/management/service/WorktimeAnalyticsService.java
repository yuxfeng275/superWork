package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.entity.User;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工时分析服务（AI 助手数据层）：基于已同步的工时明细（revenue_worklog_entry，
 * 来源 worktime.lucidata.cn 自动同步）做个人/团队维度的只读分析。
 *
 * 身份解析：ai_connector_identity 显式映射（connector_code=worktime）优先；
 * 否则按 user.real_name = employee_name 精确匹配（生产 18 名用户中 14 名可唯一命中）；
 * 命中多行或零行返回 null，由工具层转 isError 提示补录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorktimeAnalyticsService {

    private final RevenueWorklogEntryMapper worklogMapper;
    private final UserMapper userMapper;
    private final AiConnectorIdentityService identityService;

    /** 身份解析结果。 */
    public record WorktimeIdentity(Long userId, String employeeNo, String employeeName, String source) {}

    /**
     * 解析当前用户在工时系统中的身份。
     * @return null = 无法确定身份
     */
    public WorktimeIdentity resolveIdentity(Long userId) {
        String mapped = identityService.resolve(userId, AiConnectorIdentityService.CONNECTOR_WORKTIME);
        User user = userMapper.selectById(userId);
        String realName = user == null ? null : user.getRealName();
        if (mapped != null) {
            return new WorktimeIdentity(userId, mapped, realName, "IDENTITY_MAP");
        }
        if (realName == null || realName.isBlank()) {
            return null;
        }
        List<String> nos = worklogMapper.selectList(new LambdaQueryWrapper<RevenueWorklogEntry>()
                        .eq(RevenueWorklogEntry::getEmployeeName, realName)
                        .isNotNull(RevenueWorklogEntry::getEmployeeNo)
                        .ne(RevenueWorklogEntry::getEmployeeNo, "")
                        .select(RevenueWorklogEntry::getEmployeeNo))
                .stream().map(RevenueWorklogEntry::getEmployeeNo).distinct().toList();
        if (nos.size() == 1) {
            return new WorktimeIdentity(userId, nos.get(0), realName, "NAME_MATCH");
        }
        // 0 行或多个工号（同名多人都无法唯一确定）
        return null;
    }

    /** 个人月度明细行（给 AI 的结构化行）。 */
    public record MonthRow(String project, String businessLine, String workType, BigDecimal hours, String note) {}

    public record MonthlyReport(
            String month,
            String employeeNo,
            String employeeName,
            BigDecimal totalHours,
            int entryCount,
            int projectCount,
            List<MonthRow> rows,
            Map<String, BigDecimal> projectHours,
            Map<String, BigDecimal> businessLineHours,
            Map<String, BigDecimal> workTypeHours,
            List<String> monthsAvailable) {}

    /**
     * 个人某月工时报表。employeeNo 为空时按姓名聚合（同名风险由 resolveIdentity 控制）。
     */
    public MonthlyReport personalMonthly(Long userId, String month) {
        YearMonth ym = YearMonth.parse(month);
        WorktimeIdentity identity = resolveIdentity(userId);
        if (identity == null) {
            return null;
        }
        List<RevenueWorklogEntry> entries = worklogMapper.selectList(
                new LambdaQueryWrapper<RevenueWorklogEntry>()
                        .eq(RevenueWorklogEntry::getYearMonth, ym.toString())
                        .eq(RevenueWorklogEntry::getEmployeeNo, identity.employeeNo()));
        return buildReport(ym.toString(), identity.employeeNo(), identity.employeeName(), entries);
    }

    /** 近 N 个月个人工时序列（月度对比分析）。 */
    public record MonthPoint(String month, BigDecimal hours, int entries) {}

    public List<MonthPoint> personalTrend(Long userId, int months) {
        YearMonth current = YearMonth.now().minusMonths(1);
        List<MonthPoint> points = new ArrayList<>();
        for (int i = months - 1; i >= 0; i--) {
            String m = current.minusMonths(i).toString();
            List<RevenueWorklogEntry> entries = entriesForPerson(userId, m);
            if (entries == null) {
                return points;
            }
            points.add(new MonthPoint(m, sumHours(entries), entries.size()));
        }
        return points;
    }

    /** 团队某月按业务线汇总（需要 revenue:view 权限，由工具层校验）。 */
    public record TeamLineRow(String businessLine, BigDecimal hours, int people, int entries) {}

    public record TeamMonthlyReport(String month, List<TeamLineRow> lines, BigDecimal totalHours, int entryCount) {}

    public TeamMonthlyReport teamMonthly(String month) {
        YearMonth ym = YearMonth.parse(month);
        List<RevenueWorklogEntry> entries = worklogMapper.selectList(
                new LambdaQueryWrapper<RevenueWorklogEntry>()
                        .eq(RevenueWorklogEntry::getYearMonth, ym.toString())
                        .isNotNull(RevenueWorklogEntry::getEmployeeNo));
        Map<String, List<RevenueWorklogEntry>> byLine = new LinkedHashMap<>();
        for (RevenueWorklogEntry entry : entries) {
            byLine.computeIfAbsent(line(entry), k -> new ArrayList<>()).add(entry);
        }
        List<TeamLineRow> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, List<RevenueWorklogEntry>> e : byLine.entrySet()) {
            BigDecimal hours = sumHours(e.getValue());
            total = total.add(hours);
            long people = e.getValue().stream().map(RevenueWorklogEntry::getEmployeeNo).distinct().count();
            rows.add(new TeamLineRow(e.getKey(), hours, (int) people, e.getValue().size()));
        }
        rows.sort((a, b) -> b.hours().compareTo(a.hours()));
        return new TeamMonthlyReport(ym.toString(), rows, total, entries.size());
    }

    /** 全员某月缺报名单：本系统用户中，姓名能匹配但该月无工时记录的。 */
    public List<String> missingReportUsers(String month) {
        YearMonth ym = YearMonth.parse(month);
        List<String> missing = new ArrayList<>();
        for (User user : userMapper.selectList(null)) {
            if (user.getRealName() == null || user.getRealName().isBlank()
                    || "系统管理员".equals(user.getRealName()) || "admin".equals(user.getUsername())) {
                continue;
            }
            Long count = worklogMapper.selectCount(new LambdaQueryWrapper<RevenueWorklogEntry>()
                    .eq(RevenueWorklogEntry::getYearMonth, ym.toString())
                    .eq(RevenueWorklogEntry::getEmployeeName, user.getRealName()));
            if (count == null || count == 0) {
                missing.add(user.getRealName());
            }
        }
        return missing;
    }

    // ==================== 内部 ====================

    private List<RevenueWorklogEntry> entriesForPerson(Long userId, String month) {
        WorktimeIdentity identity = resolveIdentity(userId);
        if (identity == null) {
            return null;
        }
        return worklogMapper.selectList(new LambdaQueryWrapper<RevenueWorklogEntry>()
                .eq(RevenueWorklogEntry::getYearMonth, month)
                .eq(RevenueWorklogEntry::getEmployeeNo, identity.employeeNo()));
    }

    private MonthlyReport buildReport(String month, String employeeNo, String employeeName,
            List<RevenueWorklogEntry> entries) {
        Map<String, BigDecimal> projectHours = new LinkedHashMap<>();
        Map<String, BigDecimal> lineHours = new LinkedHashMap<>();
        Map<String, BigDecimal> typeHours = new LinkedHashMap<>();
        List<MonthRow> rows = new ArrayList<>();
        for (RevenueWorklogEntry entry : entries) {
            String project = entry.getProjectNameRaw() == null ? "未命名" : entry.getProjectNameRaw();
            projectHours.merge(project, entry.getHours(), BigDecimal::add);
            lineHours.merge(line(entry), entry.getHours(), BigDecimal::add);
            typeHours.merge(entry.getWorkType() == null ? "unknown" : entry.getWorkType(),
                    entry.getHours(), BigDecimal::add);
            rows.add(new MonthRow(project, entry.getBusinessLineName(), entry.getWorkType(),
                    entry.getHours(), entry.getWorkNote()));
        }
        rows.sort((a, b) -> b.hours().compareTo(a.hours()));
        return new MonthlyReport(month, employeeNo, employeeName, sumHours(entries),
                entries.size(), projectHours.size(), rows, projectHours, lineHours, typeHours, null);
    }

    private String line(RevenueWorklogEntry entry) {
        return entry.getBusinessLineName() == null || entry.getBusinessLineName().isBlank()
                ? "未分配业务线" : entry.getBusinessLineName();
    }

    private BigDecimal sumHours(List<RevenueWorklogEntry> entries) {
        return entries.stream().map(RevenueWorklogEntry::getHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }
}
