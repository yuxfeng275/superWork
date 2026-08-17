package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BuDirection;
import com.bu.management.entity.BuDirectionMilestone;
import com.bu.management.entity.BuDirectionProject;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoEstimatedEffort;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoEstimatedEffortMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.bu.management.vo.BuDashboardResponse;
import com.bu.management.vo.BuDirectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuDashboardService {

    private static final Set<String> FINISHED_REQUIREMENT_STATUSES =
            Set.of("已上线", "已交付", "已验收", "已拒绝");
    private static final Set<String> FINISHED_TASK_STATUSES = Set.of("已完成", "已测试");

    private final BuDirectionService directionService;
    private final WorklogComplianceService worklogComplianceService;
    private final YunxiaoIntegrationService integrationService;
    private final RequirementMapper requirementMapper;
    private final TaskMapper taskMapper;
    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final YunxiaoUserMappingMapper userMappingMapper;
    private final YunxiaoWorkitemCacheMapper workitemCacheMapper;
    private final YunxiaoEstimatedEffortMapper estimatedEffortMapper;
    private final YunxiaoEffortRecordMapper effortRecordMapper;

    public BuDashboardResponse getDashboard(LocalDate startDate, LocalDate endDate, int planWindowWorkdays) {
        BuDashboardResponse response = new BuDashboardResponse();
        response.setPeriodStart(startDate);
        response.setPeriodEnd(endDate);
        response.setPlanWindowWorkdays(planWindowWorkdays);
        response.setDirections(buildDirections());
        response.setWorklogs(worklogComplianceService.audit(startDate, endDate));
        response.setCapacity(buildCapacity(response.getWorklogs(), startDate, endDate, planWindowWorkdays));
        response.setIntegration(integrationService.getStatus());
        response.setSummary(buildSummary(response));
        return response;
    }

    public List<BuDirectionView> buildDirections() {
        List<BuDirection> directions = directionService.list();
        Map<Long, User> users = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Map<Long, Project> projects = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, Function.identity(), (a, b) -> a));
        return directions.stream()
                .map(direction -> toDirectionView(direction, users, projects))
                .toList();
    }

    private BuDirectionView toDirectionView(BuDirection direction, Map<Long, User> users,
                                            Map<Long, Project> projects) {
        BuDirectionView view = new BuDirectionView();
        view.setId(direction.getId());
        view.setCode(direction.getCode());
        view.setName(direction.getName());
        view.setObjective(direction.getObjective());
        view.setOwnerId(direction.getOwnerId());
        User owner = users.get(direction.getOwnerId());
        view.setOwnerName(owner == null ? null : owner.getRealName());
        view.setStartDate(direction.getStartDate());
        view.setEndDate(direction.getEndDate());
        view.setStatus(direction.getStatus());
        view.setSortOrder(direction.getSortOrder());

        List<BuDirectionProject> relations = directionService.listProjects(direction.getId());
        List<Long> projectIds = relations.stream().map(BuDirectionProject::getProjectId).toList();
        view.setProjectIds(new ArrayList<>(projectIds));
        view.setProjectNames(projectIds.stream()
                .map(projects::get)
                .filter(project -> project != null)
                .map(Project::getName)
                .toList());
        List<Requirement> requirements = projectIds.isEmpty() ? List.of()
                : requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .in(Requirement::getProjectId, projectIds));
        List<Long> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<Task> tasks = requirementIds.isEmpty() ? List.of()
                : taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getRequirementId, requirementIds));

        long completedRequirements = requirements.stream()
                .filter(item -> FINISHED_REQUIREMENT_STATUSES.contains(item.getStatus())
                        && !"已拒绝".equals(item.getStatus()))
                .count();
        long completedTasks = tasks.stream()
                .filter(item -> FINISHED_TASK_STATUSES.contains(item.getStatus()))
                .count();
        view.setRequirementCount((long) requirements.size());
        view.setCompletedRequirementCount(completedRequirements);
        view.setTaskCount((long) tasks.size());
        view.setCompletedTaskCount(completedTasks);
        view.setProgress(calculateProgress(requirements.size(), completedRequirements,
                tasks.size(), completedTasks, direction.getStatus()));

        List<BuDirectionMilestone> milestones = directionService.listMilestones(direction.getId());
        view.setMilestones(milestones.stream().map(this::toMilestoneView).toList());
        view.setHealth(resolveHealth(direction, view, milestones));
        return view;
    }

    private BuDirectionView.MilestoneView toMilestoneView(BuDirectionMilestone milestone) {
        BuDirectionView.MilestoneView view = new BuDirectionView.MilestoneView();
        view.setId(milestone.getId());
        view.setName(milestone.getName());
        view.setDueDate(milestone.getDueDate());
        view.setStatus(milestone.getStatus());
        view.setCompletedAt(milestone.getCompletedAt());
        view.setSortOrder(milestone.getSortOrder());
        view.setOverdue(!"已完成".equals(milestone.getStatus())
                && milestone.getDueDate().isBefore(LocalDate.now()));
        return view;
    }

    private BigDecimal calculateProgress(int requirementCount, long completedRequirements,
                                         int taskCount, long completedTasks, String status) {
        if ("已完成".equals(status)) {
            return BigDecimal.valueOf(100);
        }
        if (taskCount > 0) {
            return BigDecimal.valueOf(completedTasks * 100.0 / taskCount)
                    .setScale(1, RoundingMode.HALF_UP);
        }
        if (requirementCount > 0) {
            return BigDecimal.valueOf(completedRequirements * 100.0 / requirementCount)
                    .setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private String resolveHealth(BuDirection direction, BuDirectionView view,
                                 List<BuDirectionMilestone> milestones) {
        if ("已完成".equals(direction.getStatus())) {
            return "已完成";
        }
        if ("已暂停".equals(direction.getStatus())) {
            return "已暂停";
        }
        if (direction.getEndDate().isBefore(LocalDate.now())) {
            return "已延期";
        }
        boolean milestoneOverdue = milestones.stream()
                .anyMatch(item -> !"已完成".equals(item.getStatus())
                        && item.getDueDate().isBefore(LocalDate.now()));
        if (milestoneOverdue) {
            return "有风险";
        }
        long totalDays = Math.max(1, ChronoUnit.DAYS.between(direction.getStartDate(), direction.getEndDate()));
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(direction.getStartDate(), LocalDate.now()));
        BigDecimal scheduleProgress = BigDecimal.valueOf(Math.min(100, elapsed * 100.0 / totalDays));
        return scheduleProgress.subtract(view.getProgress()).compareTo(BigDecimal.valueOf(15)) > 0
                ? "有风险" : "正常";
    }

    private List<BuDashboardResponse.CapacityItem> buildCapacity(
            List<BuDashboardResponse.WorklogItem> worklogs,
            LocalDate startDate, LocalDate endDate, int planWindowWorkdays) {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, 1)
                .ne(User::getUsername, "admin")
                .orderByAsc(User::getId));
        List<Task> tasks = taskMapper.selectList(null);
        Map<Long, List<Task>> tasksByUser = tasks.stream()
                .filter(item -> item.getAssigneeId() != null)
                .collect(Collectors.groupingBy(Task::getAssigneeId));
        Map<Long, YunxiaoUserMapping> mappings = userMappingMapper.selectList(
                        new LambdaQueryWrapper<YunxiaoUserMapping>().eq(YunxiaoUserMapping::getSyncEnabled, 1))
                .stream()
                .collect(Collectors.toMap(YunxiaoUserMapping::getUserId, Function.identity(), (a, b) -> a));
        Map<String, YunxiaoWorkitemCache> cloudItemsById = workitemCacheMapper.selectList(null).stream()
                .collect(Collectors.toMap(
                        YunxiaoWorkitemCache::getYunxiaoWorkitemId,
                        Function.identity(),
                        (a, b) -> a));
        Map<String, List<YunxiaoEstimatedEffort>> estimatesByUser =
                estimatedEffortMapper.selectList(null).stream()
                        .collect(Collectors.groupingBy(YunxiaoEstimatedEffort::getYunxiaoUserId));
        Map<String, BigDecimal> cloudActualByUserWorkitem = new HashMap<>();
        effortRecordMapper.selectList(null).forEach(record -> cloudActualByUserWorkitem.merge(
                record.getYunxiaoUserId() + "|" + record.getYunxiaoWorkitemId(),
                record.getActualHours() == null ? BigDecimal.ZERO : record.getActualHours(),
                BigDecimal::add));
        Map<Long, BigDecimal> actualByUser = worklogs.stream()
                .collect(Collectors.groupingBy(
                        BuDashboardResponse.WorklogItem::getUserId,
                        Collectors.reducing(BigDecimal.ZERO,
                                BuDashboardResponse.WorklogItem::getActualHours,
                                BigDecimal::add)));
        BigDecimal expectedHours = worklogComplianceService.expectedHours(startDate, endDate);
        BigDecimal plannedCapacity = BigDecimal.valueOf(planWindowWorkdays * 8L);

        return users.stream()
                .map(user -> buildCapacityItem(user, tasksByUser.getOrDefault(user.getId(), List.of()),
                        mappings.get(user.getId()), cloudItemsById, estimatesByUser,
                        cloudActualByUserWorkitem, actualByUser, expectedHours, plannedCapacity))
                .sorted(Comparator.comparing(BuDashboardResponse.CapacityItem::getPlannedLoadRate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private BuDashboardResponse.CapacityItem buildCapacityItem(
            User user, List<Task> userTasks, YunxiaoUserMapping mapping,
            Map<String, YunxiaoWorkitemCache> cloudItemsById,
            Map<String, List<YunxiaoEstimatedEffort>> estimatesByUser,
            Map<String, BigDecimal> cloudActualByUserWorkitem,
            Map<Long, BigDecimal> actualByUser, BigDecimal expectedHours, BigDecimal plannedCapacity) {
        BuDashboardResponse.CapacityItem item = new BuDashboardResponse.CapacityItem();
        item.setUserId(user.getId());
        item.setRealName(user.getRealName());
        item.setRole(user.getRole());
        item.setYunxiaoMapped(mapping != null);
        item.setExpectedHours(expectedHours);
        item.setActualHours(actualByUser.getOrDefault(user.getId(), BigDecimal.ZERO));
        item.setActualEffortRate(rate(item.getActualHours(), expectedHours));

        List<Task> activeLocalTasks = userTasks.stream()
                .filter(task -> !FINISHED_TASK_STATUSES.contains(task.getStatus()))
                .toList();
        item.setOverdueTaskCount(activeLocalTasks.stream()
                .filter(task -> task.getEndDate() != null && task.getEndDate().isBefore(LocalDate.now()))
                .count());
        List<YunxiaoEstimatedEffort> estimates = mapping == null ? List.of()
                : estimatesByUser.getOrDefault(mapping.getYunxiaoUserId(), List.of()).stream()
                .filter(estimate -> {
                    YunxiaoWorkitemCache workitem = cloudItemsById.get(estimate.getYunxiaoWorkitemId());
                    return workitem != null && isCloudItemActive(workitem);
                })
                .toList();
        boolean plannedDataSufficient;

        if (!estimates.isEmpty()) {
            Map<String, BigDecimal> estimateByWorkitem = estimates.stream()
                    .collect(Collectors.groupingBy(
                            YunxiaoEstimatedEffort::getYunxiaoWorkitemId,
                            Collectors.reducing(BigDecimal.ZERO,
                                    effort -> effort.getEstimatedHours() == null
                                            ? BigDecimal.ZERO : effort.getEstimatedHours(),
                                    BigDecimal::add)));
            item.setActiveTaskCount(estimateByWorkitem.size());
            item.setPlannedHours(estimateByWorkitem.entrySet().stream()
                    .map(entry -> entry.getValue().subtract(cloudActualByUserWorkitem.getOrDefault(
                            mapping.getYunxiaoUserId() + "|" + entry.getKey(),
                            BigDecimal.ZERO)).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            item.setActiveWork(estimateByWorkitem.keySet().stream()
                    .map(cloudItemsById::get)
                    .filter(workitem -> workitem != null)
                    .map(YunxiaoWorkitemCache::getTitle)
                    .distinct()
                    .limit(4)
                    .toList());
            item.setDataCompleteness("云效预计工时已按负责人同步");
            plannedDataSufficient = true;
        } else {
            List<YunxiaoWorkitemCache> activeCloudItems = mapping == null ? List.of()
                    : cloudItemsById.values().stream()
                    .filter(workitem -> mapping.getYunxiaoUserId().equals(
                            workitem.getYunxiaoAssigneeId()))
                    .filter(this::isCloudItemActive)
                    .toList();
            item.setPlannedHours(activeLocalTasks.stream().map(this::remainingLocalHours)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            if (!activeCloudItems.isEmpty()) {
                item.setActiveTaskCount(activeCloudItems.size());
                item.setActiveWork(activeCloudItems.stream()
                        .map(YunxiaoWorkitemCache::getTitle)
                        .distinct()
                        .limit(4)
                        .toList());
                item.setDataCompleteness("云效活动工作项未登记预计工时，计划负荷未知");
                plannedDataSufficient = false;
            } else {
                item.setActiveTaskCount(activeLocalTasks.size());
                item.setActiveWork(activeLocalTasks.stream().map(Task::getTitle).limit(4).toList());
                item.setDataCompleteness(mapping == null
                        ? "未配置云效用户映射"
                        : "云效未登记本人预计工时，使用本地任务估算");
                plannedDataSufficient = item.getPlannedHours().signum() > 0
                        || item.getActiveTaskCount() == 0;
            }
        }
        item.setPlannedLoadRate(rate(item.getPlannedHours(), plannedCapacity));
        item.setLoadStatus(resolveLoadStatus(item.getPlannedLoadRate(), plannedDataSufficient));
        return item;
    }

    private BigDecimal remainingLocalHours(Task task) {
        BigDecimal estimated = task.getEstimatedHours() == null ? BigDecimal.ZERO : task.getEstimatedHours();
        BigDecimal actual = task.getActualHours() == null ? BigDecimal.ZERO : task.getActualHours();
        return estimated.subtract(actual).max(BigDecimal.ZERO);
    }

    private boolean isCloudItemActive(YunxiaoWorkitemCache item) {
        String status = item.getStatus() == null ? "" : item.getStatus();
        return !status.contains("完成") && !status.contains("关闭")
                && !status.contains("取消") && !status.contains("验收");
    }

    private BigDecimal rate(BigDecimal value, BigDecimal base) {
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(BigDecimal.valueOf(100))
                .divide(base, 1, RoundingMode.HALF_UP);
    }

    private String resolveLoadStatus(BigDecimal rate, boolean sufficient) {
        if (!sufficient) {
            return "未知";
        }
        if (rate.compareTo(BigDecimal.valueOf(60)) < 0) {
            return "可承接";
        }
        if (rate.compareTo(BigDecimal.valueOf(100)) <= 0) {
            return "合理";
        }
        if (rate.compareTo(BigDecimal.valueOf(120)) <= 0) {
            return "饱和";
        }
        return "超负荷";
    }

    private BuDashboardResponse.Summary buildSummary(BuDashboardResponse response) {
        BuDashboardResponse.Summary summary = new BuDashboardResponse.Summary();
        summary.setDirectionCount(response.getDirections().size());
        summary.setAtRiskDirectionCount(response.getDirections().stream()
                .filter(item -> "有风险".equals(item.getHealth()) || "已延期".equals(item.getHealth()))
                .count());
        summary.setActiveRequirementCount(requirementMapper.selectList(null).stream()
                .filter(item -> !FINISHED_REQUIREMENT_STATUSES.contains(item.getStatus()))
                .count());
        summary.setOverdueTaskCount(response.getCapacity().stream()
                .mapToLong(BuDashboardResponse.CapacityItem::getOverdueTaskCount).sum());
        summary.setOverloadedPeopleCount(response.getCapacity().stream()
                .filter(item -> "饱和".equals(item.getLoadStatus()) || "超负荷".equals(item.getLoadStatus()))
                .count());
        summary.setMissingWorklogPeopleCount(response.getWorklogs().stream()
                .filter(BuDashboardResponse.WorklogItem::isFinalResult)
                .filter(item -> "未填写".equals(item.getStatus()) || "填写不足".equals(item.getStatus()))
                .map(BuDashboardResponse.WorklogItem::getUserId)
                .distinct().count());
        return summary;
    }
}
