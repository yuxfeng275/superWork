package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.Task;
import com.bu.management.entity.User;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 团队效能视图（M3）：主管每天看一页，回答三个问题——谁卡住、谁超载、哪些项目没投递。
 *
 * <p>口径全部来自事实层与任务/制品表，不引入新的指标体系：
 * <ul>
 *   <li><b>投递率</b> = 近 7 天有项目事实的项目数 / 启用项目数。事实层空 = 夜间产出没有原料；</li>
 *   <li><b>低质事实</b> = 正文为空或摘要少于 20 字：这类事实不能拿来出方案，只能提示补材料；</li>
 *   <li><b>成员负载</b> = 未完成任务数 + 逾期任务数 + 近 30 天贡献事实数 + 待评审制品数。</li>
 * </ul>
 *
 * <p>刻意不按部门/职级重新建模：现有 project_fact.actor_id 与 task.assignee_id 已经能回答
 * 「谁在投递、谁在推进」，再加一层组织口径只会制造第二套真相。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamEfficiencyService {

    /** 默认窗口：30 天够看出趋势。 */
    public static final int DEFAULT_DAYS = 30;

    /** 投递率的固定口径：近 7 天。 */
    public static final int DELIVERY_DAYS = 7;

    private final ProjectFactMapper factMapper;

    private final AiArtifactMapper artifactMapper;

    private final TaskMapper taskMapper;

    private final ProjectMapper projectMapper;

    private final UserMapper userMapper;

    /**
     * 项目投递健康。
     *
     * @param projectId        项目 ID
     * @param projectName      项目名
     * @param facts            窗口内事实数
     * @param contributors     窗口内投递人数
     * @param lowQuality       低质事实数（无正文 / 摘要过短）
     * @param lastFactAt       最近一条事实时间
     * @param pendingArtifacts 待评审制品数
     * @param approved         窗口内已采纳制品数
     */
    public record ProjectHealth(Long projectId,
                                String projectName,
                                long facts,
                                long contributors,
                                long lowQuality,
                                LocalDateTime lastFactAt,
                                long pendingArtifacts,
                                long approved) {
    }

    /**
     * 成员负载。
     *
     * @param userId           用户 ID
     * @param name             姓名
     * @param facts            窗口内贡献事实数
     * @param openTasks        未完成任务数
     * @param overdueTasks     已逾期未完成任务数
     * @param pendingArtifacts 本人待评审制品数
     */
    public record MemberHealth(Long userId,
                               String name,
                               long facts,
                               long openTasks,
                               long overdueTasks,
                               long pendingArtifacts) {
    }

    /**
     * 团队效能报告。
     *
     * @param days                 统计窗口
     * @param since                窗口起点
     * @param activeProjects       启用项目数
     * @param deliveredProjects    近 7 天有事实的项目数
     * @param deliveryRate         投递率
     * @param totalFacts           窗口内事实总数
     * @param lowQualityFacts      窗口内低质事实数
     * @param pendingArtifacts     待评审制品总数
     * @param projects             按事实数倒序的项目
     * @param members              按事实数倒序的成员
     */
    public record Report(int days,
                         LocalDateTime since,
                         long activeProjects,
                         long deliveredProjects,
                         double deliveryRate,
                         long totalFacts,
                         long lowQualityFacts,
                         long pendingArtifacts,
                         List<ProjectHealth> projects,
                         List<MemberHealth> members) {
    }

    /** 出报告；任何一段查询失败只降级该段，不整页 500。 */
    public Report overview(Integer days) {
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, 90);
        LocalDateTime since = LocalDateTime.now().minusDays(window);
        LocalDateTime deliverySince = LocalDateTime.now().minusDays(DELIVERY_DAYS);
        LocalDate today = LocalDate.now();

        List<ProjectFact> facts = queryFacts(since);
        List<ProjectFact> recentFacts = facts.stream()
                .filter(fact -> fact.getCreatedAt() != null && fact.getCreatedAt().isAfter(deliverySince))
                .toList();
        List<AiArtifact> artifacts = queryArtifacts(since);
        List<AiArtifact> pending = queryPendingArtifacts();
        List<Task> openTasks = queryOpenTasks();
        List<Project> projects = queryProjects();

        Map<Long, List<ProjectFact>> factsByProject = facts.stream()
                .filter(fact -> fact.getProjectId() != null)
                .collect(Collectors.groupingBy(ProjectFact::getProjectId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<AiArtifact>> pendingByProject = pending.stream()
                .filter(item -> item.getProjectId() != null)
                .collect(Collectors.groupingBy(AiArtifact::getProjectId));
        Map<Long, Long> approvedByProject = artifacts.stream()
                .filter(item -> item.getProjectId() != null)
                .filter(item -> "APPROVED".equals(item.getStatus())
                        || "APPLIED".equals(item.getStatus()))
                .collect(Collectors.groupingBy(AiArtifact::getProjectId, Collectors.counting()));

        List<ProjectHealth> projectRows = new ArrayList<>();
        for (Project project : projects) {
            List<ProjectFact> projectFacts = factsByProject.getOrDefault(project.getId(), List.of());
            LocalDateTime lastFactAt = projectFacts.stream()
                    .map(ProjectFact::getCreatedAt)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            projectRows.add(new ProjectHealth(
                    project.getId(),
                    project.getName(),
                    projectFacts.size(),
                    projectFacts.stream().map(ProjectFact::getActorId).filter(Objects::nonNull).distinct().count(),
                    projectFacts.stream().filter(TeamEfficiencyService::lowQuality).count(),
                    lastFactAt,
                    pendingByProject.getOrDefault(project.getId(), List.of()).size(),
                    approvedByProject.getOrDefault(project.getId(), 0L)));
        }
        projectRows.sort(Comparator.comparingLong(ProjectHealth::facts).reversed());

        Map<Long, List<ProjectFact>> factsByActor = facts.stream()
                .filter(fact -> fact.getActorId() != null)
                .collect(Collectors.groupingBy(ProjectFact::getActorId));
        Map<Long, List<Task>> tasksByAssignee = openTasks.stream()
                .filter(task -> task.getAssigneeId() != null)
                .collect(Collectors.groupingBy(Task::getAssigneeId));
        Map<Long, Long> pendingByOwner = pending.stream()
                .filter(item -> item.getOwnerUserId() != null)
                .collect(Collectors.groupingBy(AiArtifact::getOwnerUserId, Collectors.counting()));

        Set<Long> userIds = new java.util.HashSet<>();
        userIds.addAll(factsByActor.keySet());
        userIds.addAll(tasksByAssignee.keySet());
        userIds.addAll(pendingByOwner.keySet());
        Map<Long, String> names = userNames(userIds);

        List<MemberHealth> memberRows = new ArrayList<>();
        for (Long userId : userIds) {
            List<Task> own = tasksByAssignee.getOrDefault(userId, List.of());
            long overdue = own.stream()
                    .filter(task -> task.getEndDate() != null && task.getEndDate().isBefore(today))
                    .count();
            memberRows.add(new MemberHealth(
                    userId,
                    names.getOrDefault(userId, "用户#" + userId),
                    factsByActor.getOrDefault(userId, List.of()).size(),
                    own.size(),
                    overdue,
                    pendingByOwner.getOrDefault(userId, 0L)));
        }
        memberRows.sort(Comparator.comparingLong(MemberHealth::facts).reversed()
                .thenComparing(Comparator.comparingLong(MemberHealth::overdueTasks).reversed()));

        long delivered = recentFacts.stream().map(ProjectFact::getProjectId)
                .filter(Objects::nonNull).distinct().count();
        double deliveryRate = projects.isEmpty() ? 0d
                : Math.round(delivered * 10000d / projects.size()) / 10000d;

        return new Report(window, since, projects.size(), delivered, deliveryRate,
                facts.size(), facts.stream().filter(TeamEfficiencyService::lowQuality).count(),
                pending.size(), projectRows, memberRows);
    }

    /** 低质事实：没有正文，或摘要短到无法判断（20 字以下）。 */
    private static boolean lowQuality(ProjectFact fact) {
        if (!StringUtils.hasText(fact.getBody())) {
            return true;
        }
        return !StringUtils.hasText(fact.getDigest()) || fact.getDigest().strip().length() < 20;
    }

    private List<ProjectFact> queryFacts(LocalDateTime since) {
        try {
            List<ProjectFact> rows = factMapper.selectList(new LambdaQueryWrapper<ProjectFact>()
                    .ge(ProjectFact::getCreatedAt, since));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("团队效能：事实查询失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    private List<AiArtifact> queryArtifacts(LocalDateTime since) {
        try {
            List<AiArtifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<AiArtifact>()
                    .ge(AiArtifact::getCreatedAt, since));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("团队效能：制品查询失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    private List<AiArtifact> queryPendingArtifacts() {
        try {
            List<AiArtifact> rows = artifactMapper.selectList(new LambdaQueryWrapper<AiArtifact>()
                    .in(AiArtifact::getStatus, "DRAFT", "IN_REVIEW"));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("团队效能：待评审制品查询失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Task> queryOpenTasks() {
        try {
            List<Task> rows = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                    .notIn(Task::getStatus, "DONE", "CANCELLED", "CLOSED", "已完成")
                    .isNotNull(Task::getAssigneeId));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("团队效能：任务查询失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Project> queryProjects() {
        try {
            List<Project> rows = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .eq(Project::getStatus, 1));
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("团队效能：项目查询失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<Long, String> userNames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        try {
            List<User> users = userMapper.selectBatchIds(ids);
            if (users == null) {
                return new HashMap<>();
            }
            Map<Long, String> names = new HashMap<>();
            for (User user : users) {
                names.put(user.getId(), StringUtils.hasText(user.getRealName())
                        ? user.getRealName() : user.getUsername());
            }
            return names;
        } catch (Exception e) {
            log.warn("团队效能：用户查询失败，按 ID 展示: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
