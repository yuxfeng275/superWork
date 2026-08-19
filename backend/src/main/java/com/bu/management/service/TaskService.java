package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.dto.UpdateTaskDTO;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.entity.User;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.TaskOverviewItem;
import com.bu.management.vo.TaskOverviewResponse;
import com.bu.management.vo.TaskOverviewSummary;
import com.bu.management.vo.WorkItemOverviewItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final YunxiaoWorkItemQueryService yunxiaoWorkItemQueryService;

    /**
     * 创建任务
     */
    /** Creates a task with the authenticated actor; body createdBy is ignored. */
    @Transactional
    public Task createTask(CreateTaskDTO dto, Long actorId) {
        if (actorId == null) {
            throw new RuntimeException("当前登录用户不存在");
        }
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 创建任务
        Task task = new Task();
        task.setRequirementId(dto.getRequirementId());
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setAssigneeId(dto.getAssigneeId());
        task.setTaskType(resolveTaskType(dto.getTaskType()));
        task.setEstimatedHours(dto.getEstimatedHours());
        task.setStatus("待开始");
        task.setCreatedBy(actorId);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.insert(task);
        return task;
    }

    /**
     * 更新任务
     */
    @Transactional
    public Task updateTask(Long id, UpdateTaskDTO dto) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        if (dto.getTitle() != null) {
            task.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            task.setDescription(dto.getDescription());
        }
        if (dto.getAssigneeId() != null) {
            task.setAssigneeId(dto.getAssigneeId());
        }
        if (dto.getEstimatedHours() != null) {
            task.setEstimatedHours(dto.getEstimatedHours());
        }
        if (dto.getActualHours() != null) {
            task.setActualHours(dto.getActualHours());
        }
        if (dto.getStatus() != null) {
            task.setStatus(dto.getStatus());
        }
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(task);
        return task;
    }

    /**
     * 查询任务详情
     */
    public Task getTaskById(Long id) {
        return taskMapper.selectById(id);
    }

    /**
     * 查询需求的任务列表
     */
    public List<Task> getTasksByRequirementId(Long requirementId) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Task::getRequirementId, requirementId);
        wrapper.orderByDesc(Task::getCreatedAt);
        return taskMapper.selectList(wrapper);
    }

    /**
     * 分页查询任务
     */
    public Page<Task> getTasksPage(int page, int size, Long requirementId, Long assigneeId, String status) {
        Page<Task> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        if (requirementId != null) {
            wrapper.eq(Task::getRequirementId, requirementId);
        }
        if (assigneeId != null) {
            wrapper.eq(Task::getAssigneeId, assigneeId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Task::getStatus, status);
        }

        wrapper.orderByDesc(Task::getCreatedAt);
        return taskMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 查询任务概览。用于任务管理页按项目、按人员聚合查看。
     */
    public TaskOverviewResponse getTaskOverview(Long projectId, Long assigneeId, String status, String keyword) {
        return getTaskOverview(null, null, projectId, assigneeId, status, keyword);
    }

    public TaskOverviewResponse getTaskOverview(Long userId, String role, Long projectId,
                                                Long assigneeId, String status, String keyword) {
        TaskOverviewResponse response = new TaskOverviewResponse();
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        if (projectId != null) {
            Set<Long> projectIds = resolveProjectScope(projectId);
            List<Requirement> scopedRequirements = getRequirementsByProjectIds(projectIds);
            if (scopedRequirements.isEmpty()) {
                wrapper.eq(Task::getId, -1L);
            }

            Set<Long> requirementIds = scopedRequirements.stream()
                    .map(Requirement::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!requirementIds.isEmpty()) {
                wrapper.in(Task::getRequirementId, requirementIds);
            }
        }

        if (assigneeId != null) {
            wrapper.eq(Task::getAssigneeId, assigneeId);
        }
        boolean normalizedStatusFilter = isNormalizedStatus(status);
        if (StringUtils.hasText(status) && !normalizedStatusFilter) {
            wrapper.eq(Task::getStatus, status.trim());
        }

        wrapper.orderByDesc(Task::getUpdatedAt).orderByDesc(Task::getCreatedAt);
        List<Task> selectedTasks = taskMapper.selectList(wrapper);
        List<Task> tasks = selectedTasks == null ? List.of() : selectedTasks;

        Map<Long, Requirement> requirementMap = loadRequirementMap(tasks);
        Map<Long, Project> projectMap = loadProjectMap(requirementMap.values());
        Map<Long, User> userMap = loadUserMap(tasks);

        List<TaskOverviewItem> items = new ArrayList<>(tasks.stream()
                .map(task -> toOverviewItem(task, requirementMap, projectMap, userMap))
                .filter(item -> !normalizedStatusFilter
                        || status.equalsIgnoreCase(item.getNormalizedStatus()))
                .filter(item -> matchesKeyword(item, keyword))
                .toList());
        if (yunxiaoWorkItemQueryService != null) {
            String normalizedStatus = normalizeStatusFilter(status);
            yunxiaoWorkItemQueryService.listCloudItems(
                            YunxiaoWorkItemConstants.CATEGORY_TASK, userId, role, projectId,
                            assigneeId, normalizedStatus, keyword)
                    .stream().map(this::toCloudTaskItem).forEach(items::add);
        }
        items.sort(java.util.Comparator.comparing(TaskOverviewItem::getUpdatedAt,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));

        response.setTasks(items);
        response.setSummary(buildSummary(items));
        response.setAnalysis(WorkItemAnalysisBuilder.build(items));
        return response;
    }

    /**
     * 删除任务
     */
    @Transactional
    public void deleteTask(Long id) {
        taskMapper.deleteById(id);
    }

    private String resolveTaskType(String taskType) {
        return StringUtils.hasText(taskType) ? taskType.trim() : "开发任务";
    }

    private Set<Long> resolveProjectScope(Long projectId) {
        Set<Long> projectIds = new LinkedHashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(projectId);

        while (!queue.isEmpty()) {
            Long currentId = queue.poll();
            if (currentId == null || !projectIds.add(currentId)) {
                continue;
            }

            LambdaQueryWrapper<Project> childWrapper = new LambdaQueryWrapper<>();
            childWrapper.eq(Project::getParentId, currentId);
            List<Project> children = projectMapper.selectList(childWrapper);
            for (Project child : children) {
                queue.add(child.getId());
            }
        }

        return projectIds;
    }

    private List<Requirement> getRequirementsByProjectIds(Set<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(Requirement::getProjectId, projectIds);
        return requirementMapper.selectList(wrapper);
    }

    private Map<Long, Requirement> loadRequirementMap(List<Task> tasks) {
        Set<Long> requirementIds = tasks.stream()
                .map(Task::getRequirementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requirementIds.isEmpty()) {
            return Map.of();
        }
        return mapById(requirementMapper.selectBatchIds(requirementIds), Requirement::getId);
    }

    private Map<Long, Project> loadProjectMap(Collection<Requirement> requirements) {
        Set<Long> projectIds = requirements.stream()
                .map(Requirement::getProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return mapById(projectMapper.selectBatchIds(projectIds), Project::getId);
    }

    private Map<Long, User> loadUserMap(List<Task> tasks) {
        Set<Long> userIds = tasks.stream()
                .map(Task::getAssigneeId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return mapById(userMapper.selectBatchIds(userIds), User::getId);
    }

    private <T> Map<Long, T> mapById(List<T> records, Function<T, Long> idGetter) {
        if (records == null || records.isEmpty()) {
            return Map.of();
        }

        return records.stream()
                .filter(Objects::nonNull)
                .filter(record -> idGetter.apply(record) != null)
                .collect(Collectors.toMap(idGetter, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private TaskOverviewItem toOverviewItem(
            Task task,
            Map<Long, Requirement> requirementMap,
            Map<Long, Project> projectMap,
            Map<Long, User> userMap
    ) {
        Requirement requirement = requirementMap.get(task.getRequirementId());
        Project project = requirement == null ? null : projectMap.get(requirement.getProjectId());
        User assignee = task.getAssigneeId() == null ? null : userMap.get(task.getAssigneeId());

        TaskOverviewItem item = new TaskOverviewItem();
        item.setRecordKey("local:" + task.getId());
        item.setDataSource("LOCAL");
        item.setReadOnly(false);
        item.setId(task.getId());
        item.setRequirementId(task.getRequirementId());
        item.setRequirementNo(requirement == null ? null : requirement.getReqNo());
        item.setRequirementTitle(requirement == null ? null : requirement.getTitle());
        item.setProjectId(project == null ? null : project.getId());
        item.setProjectName(project == null ? null : project.getName());
        item.setProjectFullPath(project == null ? null : project.getFullPath());
        if (project != null) {
            item.setProjectIds(List.of(project.getId()));
            item.setProjectNames(List.of(project.getName()));
        }
        item.setAssigneeId(task.getAssigneeId());
        item.setAssigneeName(assignee == null ? null : assignee.getRealName());
        item.setAssigneeUsername(assignee == null ? null : assignee.getUsername());
        item.setTitle(task.getTitle());
        item.setDescription(task.getDescription());
        item.setTaskType(task.getTaskType());
        item.setEstimatedHours(task.getEstimatedHours());
        item.setActualHours(task.getActualHours());
        item.setStatus(task.getStatus());
        item.setNormalizedStatus(YunxiaoWorkItemConstants.normalizeStatus(null, task.getStatus()));
        item.setStartDate(task.getStartDate());
        item.setEndDate(task.getEndDate());
        item.setDueDate(task.getEndDate());
        applyOverdueState(item);
        item.setCreatedAt(task.getCreatedAt());
        item.setUpdatedAt(task.getUpdatedAt());
        return item;
    }

    private TaskOverviewItem toCloudTaskItem(WorkItemOverviewItem cloud) {
        TaskOverviewItem item = new TaskOverviewItem();
        item.setRecordKey(cloud.getRecordKey());
        item.setDataSource(cloud.getDataSource());
        item.setReadOnly(true);
        item.setYunxiaoWorkitemId(cloud.getYunxiaoWorkitemId());
        item.setSerialNumber(cloud.getSerialNumber());
        item.setCategory(cloud.getCategory());
        item.setTitle(cloud.getTitle());
        item.setDescription(cloud.getDescription());
        item.setProjectIds(cloud.getProjectIds());
        item.setProjectNames(cloud.getProjectNames());
        item.setProjectId(cloud.getProjectId());
        item.setProjectName(cloud.getProjectName());
        item.setProjectFullPath(cloud.getProjectFullPath());
        item.setAssigneeId(cloud.getAssigneeId());
        item.setAssigneeKey(cloud.getAssigneeKey());
        item.setAssigneeName(cloud.getAssigneeName());
        item.setAssigneeUsername(cloud.getAssigneeUsername());
        item.setStatus(cloud.getStatus());
        item.setNormalizedStatus(cloud.getNormalizedStatus());
        item.setEstimatedHours(cloud.getEstimatedHours());
        item.setActualHours(cloud.getActualHours());
        item.setCreatedAt(cloud.getCreatedAt());
        item.setUpdatedAt(cloud.getUpdatedAt());
        item.setLastSyncedAt(cloud.getLastSyncedAt());
        item.setDueDate(cloud.getDueDate());
        item.setOverdueIncomplete(cloud.isOverdueIncomplete());
        item.setOverdueDays(cloud.getOverdueDays());
        return item;
    }

    private void applyOverdueState(WorkItemOverviewItem item) {
        boolean overdue = item.getDueDate() != null
                && item.getDueDate().isBefore(LocalDate.now())
                && !YunxiaoWorkItemConstants.STATUS_COMPLETED.equals(item.getNormalizedStatus());
        item.setOverdueIncomplete(overdue);
        item.setOverdueDays(overdue ? ChronoUnit.DAYS.between(item.getDueDate(), LocalDate.now()) : null);
    }

    private boolean isNormalizedStatus(String status) {
        return StringUtils.hasText(status) && Set.of(
                YunxiaoWorkItemConstants.STATUS_PENDING,
                YunxiaoWorkItemConstants.STATUS_IN_PROGRESS,
                YunxiaoWorkItemConstants.STATUS_COMPLETED,
                YunxiaoWorkItemConstants.STATUS_OTHER
        ).contains(status.toUpperCase(Locale.ROOT));
    }

    private String normalizeStatusFilter(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        if (isNormalizedStatus(status)) {
            return status.toUpperCase(Locale.ROOT);
        }
        return YunxiaoWorkItemConstants.normalizeStatus(null, status);
    }

    private boolean matchesKeyword(TaskOverviewItem item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }

        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>();
        fields.add(item.getTitle());
        fields.add(item.getDescription());
        fields.add(item.getTaskType());
        fields.add(item.getRequirementNo());
        fields.add(item.getRequirementTitle());
        fields.add(item.getProjectName());
        fields.add(item.getProjectFullPath());
        fields.add(item.getAssigneeName());
        fields.add(item.getAssigneeUsername());

        return fields.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(normalizedKeyword));
    }

    private TaskOverviewSummary buildSummary(List<TaskOverviewItem> items) {
        TaskOverviewSummary summary = new TaskOverviewSummary();
        summary.setTotalCount((long) items.size());
        summary.setPendingCount(countNormalizedStatus(items, YunxiaoWorkItemConstants.STATUS_PENDING));
        summary.setInProgressCount(countNormalizedStatus(items, YunxiaoWorkItemConstants.STATUS_IN_PROGRESS));
        summary.setCompletedCount(countNormalizedStatus(items, YunxiaoWorkItemConstants.STATUS_COMPLETED));
        summary.setTestedCount(countStatus(items, "已测试"));
        summary.setUnassignedCount(items.stream().filter(item -> item.getAssigneeId() == null).count());
        summary.setTotalEstimatedHours(sumHours(items, true));
        summary.setTotalActualHours(sumHours(items, false));
        summary.setStatusCounts(buildStatusCounts(items));
        return summary;
    }

    private long countStatus(List<TaskOverviewItem> items, String status) {
        return items.stream()
                .filter(item -> status.equals(item.getStatus()))
                .count();
    }

    private long countNormalizedStatus(List<TaskOverviewItem> items, String status) {
        return items.stream()
                .filter(item -> status.equals(item.getNormalizedStatus()))
                .count();
    }

    private BigDecimal sumHours(List<TaskOverviewItem> items, boolean estimated) {
        return items.stream()
                .map(item -> estimated ? item.getEstimatedHours() : item.getActualHours())
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Long> buildStatusCounts(List<TaskOverviewItem> items) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("待开始", 0L);
        counts.put("进行中", 0L);
        counts.put("已完成", 0L);
        counts.put("已测试", 0L);

        for (TaskOverviewItem item : items) {
            String status = StringUtils.hasText(item.getStatus()) ? item.getStatus() : "未设置";
            counts.put(status, counts.getOrDefault(status, 0L) + 1);
        }
        return counts;
    }
}
