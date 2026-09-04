package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.dto.BuKeyMatterWeeklyUpdateRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.BuKeyMatterParticipant;
import com.bu.management.entity.BuKeyMatterWeeklyUpdate;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.mapper.BuKeyMatterWeeklyUpdateMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.BuKeyMatterParticipantView;
import com.bu.management.vo.BuKeyMatterView;
import com.bu.management.vo.BuKeyMatterWeeklyUpdateView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuKeyMatterService {

    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2");
    private static final Set<String> STATUSES = Set.of(
            "未开始", "推进中", "有风险", "已阻塞", "已完成", "已暂停");

    private final BuKeyMatterMapper matterMapper;
    private final BuKeyMatterWeeklyUpdateMapper weeklyUpdateMapper;
    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final BuKeyMatterParticipantMapper participantMapper;
    private final BuKeyMatterAccessService accessService;

    @Transactional
    public BuKeyMatter create(BuKeyMatterRequest request, Long userId, String username) {
        accessService.requireCreate(request == null ? null : request.getOwnerId(), userId, username);
        NormalizedMatter normalized = validate(request);
        List<Long> participantIds = normalizeParticipantIds(request);
        validateParticipantIds(participantIds, request.getOwnerId());
        BuKeyMatter matter = new BuKeyMatter();
        apply(matter, request, normalized);
        matter.setCreatedBy(userId);
        matter.setCreatedAt(LocalDateTime.now());
        matter.setUpdatedAt(LocalDateTime.now());
        matterMapper.insert(matter);
        syncParticipants(matter.getId(), participantIds);
        return matter;
    }

    public List<BuKeyMatterView> list(String keyword, String status, String priority,
                                      Long ownerId, Long projectId) {
        LambdaQueryWrapper<BuKeyMatter> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String trimmedKeyword = keyword.trim();
            query.and(wrapper -> wrapper.like(BuKeyMatter::getTitle, trimmedKeyword)
                    .or().like(BuKeyMatter::getDescription, trimmedKeyword));
        }
        query.eq(StringUtils.hasText(status), BuKeyMatter::getStatus, status)
                .eq(StringUtils.hasText(priority), BuKeyMatter::getPriority, priority)
                .eq(ownerId != null, BuKeyMatter::getOwnerId, ownerId);
        if (projectId != null) {
            if (projectId == 0L) {
                // 哨兵值 0：仅返回未关联项目（project_id IS NULL）的事项
                query.isNull(BuKeyMatter::getProjectId);
            } else {
                List<Project> allProjects = projectMapper.selectList(null);
                Set<Long> projectScope = allProjects.stream()
                        .filter(project -> projectId.equals(project.getId()) || projectId.equals(project.getParentId()))
                        .map(Project::getId)
                        .collect(Collectors.toSet());
                query.in(BuKeyMatter::getProjectId, projectScope);
            }
        }
        query.orderByAsc(BuKeyMatter::getSortOrder)
                .orderByDesc(BuKeyMatter::getUpdatedAt);
        return buildViews(matterMapper.selectList(query), currentWeekStart());
    }

    public BuKeyMatterView get(Long id) {
        return buildViews(List.of(findMatter(id)), currentWeekStart()).get(0);
    }

    @Transactional
    public BuKeyMatter update(Long id, BuKeyMatterRequest request, Long userId, String username) {
        BuKeyMatter matter = findMatterForUpdate(id);
        accessService.requireEdit(matter, userId, username);
        boolean wasCompleted = "已完成".equals(matter.getStatus());
        LocalDateTime originalCompletedAt = matter.getCompletedAt();
        NormalizedMatter normalized = validate(request);
        List<Long> participantIds = normalizeUpdateParticipantIds(matter.getId(), request);
        if (request.getParticipantIds() != null) {
            validateParticipantIds(participantIds, request.getOwnerId());
        }
        apply(matter, request, normalized);
        if ("已完成".equals(normalized.status()) && wasCompleted) {
            matter.setCompletedAt(originalCompletedAt == null ? LocalDateTime.now() : originalCompletedAt);
        }
        matter.setUpdatedAt(LocalDateTime.now());
        matterMapper.updateById(matter);
        syncParticipants(matter.getId(), participantIds);
        return matter;
    }

    @Transactional
    public void delete(Long id) {
        findMatterForUpdate(id);
        participantMapper.delete(new LambdaQueryWrapper<BuKeyMatterParticipant>()
                .eq(BuKeyMatterParticipant::getKeyMatterId, id));
        matterMapper.deleteById(id);
    }

    @Transactional
    public BuKeyMatterWeeklyUpdate upsertWeeklyUpdate(Long matterId, LocalDate weekStartDate,
                                                       BuKeyMatterWeeklyUpdateRequest request,
                                                       Long userId, String username) {
        validateWeekStart(weekStartDate);
        BuKeyMatter matter = findMatterForUpdate(matterId);
        accessService.requireFeedback(matter, userId, username);
        List<BuKeyMatterWeeklyUpdate> existingUpdates = weeklyUpdateMapper.selectList(
                new LambdaQueryWrapper<BuKeyMatterWeeklyUpdate>()
                        .eq(BuKeyMatterWeeklyUpdate::getKeyMatterId, matterId)
                        .orderByDesc(BuKeyMatterWeeklyUpdate::getWeekStartDate));
        BuKeyMatterWeeklyUpdate update = existingUpdates.stream()
                .filter(item -> weekStartDate.equals(item.getWeekStartDate()))
                .findFirst()
                .orElse(null);
        boolean creating = update == null;
        if (creating && "已完成".equals(matter.getStatus())) {
            throw new RuntimeException("已完成事项无需新增周进展");
        }
        NormalizedWeeklyUpdate normalized = validate(request);
        if (creating) {
            update = new BuKeyMatterWeeklyUpdate();
        }
        LocalDateTime now = LocalDateTime.now();
        update.setKeyMatterId(matterId);
        update.setWeekStartDate(weekStartDate);
        update.setStatus(normalized.status());
        update.setProgress(normalized.progress());
        update.setProgressSummary(request.getProgressSummary().trim());
        update.setIssues(trimToNull(request.getIssues()));
        update.setNextWeekPlan(trimToNull(request.getNextWeekPlan()));
        update.setSupportNeeded(trimToNull(request.getSupportNeeded()));
        update.setUpdatedAt(now);
        if (creating) {
            update.setCreatedBy(userId);
            update.setCreatedAt(now);
            weeklyUpdateMapper.insert(update);
        } else {
            weeklyUpdateMapper.updateById(update);
        }

        if (!"已完成".equals(matter.getStatus())) {
            LocalDate latestWeek = existingUpdates.stream()
                    .map(BuKeyMatterWeeklyUpdate::getWeekStartDate)
                    .filter(date -> date != null)
                    .max(LocalDate::compareTo)
                    .orElse(null);
            if (latestWeek == null || !weekStartDate.isBefore(latestWeek)) {
                synchronizeMatter(matter, normalized.status(), normalized.progress(), now);
                matterMapper.updateById(matter);
            }
        }
        return update;
    }

    @Transactional
    public void deleteWeeklyUpdate(Long matterId, LocalDate weekStartDate,
                                   Long userId, String username) {
        validateWeekStart(weekStartDate);
        BuKeyMatter matter = findMatterForUpdate(matterId);
        accessService.requireFeedback(matter, userId, username);
        weeklyUpdateMapper.delete(new LambdaQueryWrapper<BuKeyMatterWeeklyUpdate>()
                .eq(BuKeyMatterWeeklyUpdate::getKeyMatterId, matterId)
                .eq(BuKeyMatterWeeklyUpdate::getWeekStartDate, weekStartDate));
    }

    public List<BuKeyMatterView> meeting(LocalDate weekStartDate) {
        validateWeekStart(weekStartDate);
        LocalDate weekEndDate = weekStartDate.plusDays(6);
        List<BuKeyMatter> included = matterMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .filter(matter -> !"已完成".equals(matter.getStatus())
                        || completedDuring(matter, weekStartDate, weekEndDate))
                .toList();
        List<BuKeyMatterView> views = new ArrayList<>(buildViews(included, weekStartDate));
        views.sort(Comparator
                .comparingInt((BuKeyMatterView matter) -> priorityRank(matter.getPriority()))
                .thenComparingInt(matter -> riskRank(matter.getStatus()))
                .thenComparingInt(matter -> "已完成".equals(matter.getStatus()) ? 1 : 0)
                .thenComparing(BuKeyMatterView::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(BuKeyMatterView::getPlannedCompletionDate,
                        Comparator.nullsLast(LocalDate::compareTo))
                .thenComparing(BuKeyMatterView::getId));
        return views;
    }

    private NormalizedMatter validate(BuKeyMatterRequest request) {
        if (request == null) {
            throw new RuntimeException("事项信息不能为空");
        }
        if (!StringUtils.hasText(request.getTitle())) {
            throw new RuntimeException("事项标题不能为空");
        }
        if (request.getTitle().trim().length() > 200) {
            throw new RuntimeException("事项标题不能超过200字");
        }
        if (request.getStartDate() == null || request.getPlannedCompletionDate() == null) {
            throw new RuntimeException("开始日期和计划完成日期不能为空");
        }
        if (request.getPlannedCompletionDate().isBefore(request.getStartDate())) {
            throw new RuntimeException("计划完成日期不能早于开始日期");
        }
        User owner = request.getOwnerId() == null ? null : userMapper.selectById(request.getOwnerId());
        if (owner == null || !Integer.valueOf(1).equals(owner.getStatus())) {
            throw new RuntimeException("负责人不存在或已停用");
        }
        if (request.getProjectId() != null) {
            Project project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new RuntimeException("关联项目不存在");
            }
        }
        String priority = StringUtils.hasText(request.getPriority()) ? request.getPriority() : "P1";
        String status = StringUtils.hasText(request.getStatus()) ? request.getStatus() : "未开始";
        int progress = request.getProgress() == null ? 0 : request.getProgress();
        if (!PRIORITIES.contains(priority)) {
            throw new RuntimeException("不支持的优先级");
        }
        if (!STATUSES.contains(status)) {
            throw new RuntimeException("不支持的事项状态");
        }
        if (progress < 0 || progress > 100) {
            throw new RuntimeException("事项进度必须在0到100之间");
        }
        if ("已完成".equals(status)) {
            progress = 100;
        } else if (progress == 100) {
            throw new RuntimeException("进度达到100%时状态必须为已完成");
        }
        return new NormalizedMatter(priority, status, progress);
    }

    private NormalizedWeeklyUpdate validate(BuKeyMatterWeeklyUpdateRequest request) {
        if (request == null || !StringUtils.hasText(request.getProgressSummary())) {
            throw new RuntimeException("本周进展不能为空");
        }
        if (!StringUtils.hasText(request.getStatus()) || !STATUSES.contains(request.getStatus())) {
            throw new RuntimeException("不支持的事项状态");
        }
        int progress = request.getProgress() == null ? -1 : request.getProgress();
        if (progress < 0 || progress > 100) {
            throw new RuntimeException("事项进度必须在0到100之间");
        }
        if ("已完成".equals(request.getStatus())) {
            progress = 100;
        } else if (progress == 100) {
            throw new RuntimeException("进度达到100%时状态必须为已完成");
        }
        return new NormalizedWeeklyUpdate(request.getStatus(), progress);
    }

    private void apply(BuKeyMatter matter, BuKeyMatterRequest request, NormalizedMatter normalized) {
        matter.setTitle(request.getTitle().trim());
        matter.setDescription(StringUtils.hasText(request.getDescription())
                ? request.getDescription().trim() : null);
        matter.setProjectId(request.getProjectId());
        matter.setOwnerId(request.getOwnerId());
        matter.setPriority(normalized.priority());
        matter.setStatus(normalized.status());
        matter.setProgress(normalized.progress());
        matter.setStartDate(request.getStartDate());
        matter.setPlannedCompletionDate(request.getPlannedCompletionDate());
        matter.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        matter.setCompletedAt("已完成".equals(normalized.status()) ? LocalDateTime.now() : null);
    }

    private BuKeyMatter findMatter(Long id) {
        BuKeyMatter matter = id == null ? null : matterMapper.selectById(id);
        if (matter == null) {
            throw new ResourceNotFoundException("大事儿不存在");
        }
        return matter;
    }

    private BuKeyMatter findMatterForUpdate(Long id) {
        BuKeyMatter matter = id == null ? null : matterMapper.selectByIdForUpdate(id);
        if (matter == null) {
            throw new ResourceNotFoundException("大事儿不存在");
        }
        return matter;
    }

    private List<BuKeyMatterView> buildViews(List<BuKeyMatter> matters, LocalDate selectedWeek) {
        if (matters == null || matters.isEmpty()) {
            return List.of();
        }
        List<Long> matterIds = matters.stream().map(BuKeyMatter::getId).toList();
        List<BuKeyMatterWeeklyUpdate> updates = weeklyUpdateMapper.selectList(
                new LambdaQueryWrapper<BuKeyMatterWeeklyUpdate>()
                        .in(BuKeyMatterWeeklyUpdate::getKeyMatterId, matterIds)
                        .orderByDesc(BuKeyMatterWeeklyUpdate::getWeekStartDate));
        if (updates == null) {
            updates = List.of();
        }
        Map<Long, List<BuKeyMatterWeeklyUpdate>> updatesByMatter = updates.stream()
                .collect(Collectors.groupingBy(BuKeyMatterWeeklyUpdate::getKeyMatterId));
        Map<Long, User> owners = loadOwners(matters);
        Map<Long, Project> projects = loadProjects(matters);
        List<BuKeyMatterParticipant> participantRelations = participantMapper.selectList(
                new LambdaQueryWrapper<BuKeyMatterParticipant>()
                        .in(BuKeyMatterParticipant::getKeyMatterId, matterIds)
                        .orderByAsc(BuKeyMatterParticipant::getId));
        if (participantRelations == null) {
            participantRelations = List.of();
        }
        Map<Long, List<BuKeyMatterParticipant>> participantsByMatter = participantRelations.stream()
                .collect(Collectors.groupingBy(BuKeyMatterParticipant::getKeyMatterId));
        Map<Long, User> participantUsers = loadParticipantUsers(participantRelations);
        LocalDate today = LocalDate.now();
        List<BuKeyMatterView> views = new ArrayList<>();
        for (BuKeyMatter matter : matters) {
            BuKeyMatterView view = toView(matter);
            view.setParticipants(toParticipantViews(matter.getId(), participantsByMatter, participantUsers));
            User owner = owners.get(matter.getOwnerId());
            if (owner != null) {
                view.setOwnerName(owner.getRealName());
            }
            if (matter.getProjectId() != null) {
                Project project = projects.get(matter.getProjectId());
                if (project != null) {
                    Project rootProject = project;
                    while (rootProject.getParentId() != null && projects.containsKey(rootProject.getParentId())) {
                        rootProject = projects.get(rootProject.getParentId());
                    }
                    view.setProjectRootId(rootProject.getId());
                    view.setProjectRootName(rootProject.getName());
                    view.setProjectName(rootProject.getId().equals(project.getId())
                            ? rootProject.getName()
                            : rootProject.getName() + "-" + project.getName());
                }
            }
            List<BuKeyMatterWeeklyUpdateView> weeklyViews = updatesByMatter
                    .getOrDefault(matter.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(BuKeyMatterWeeklyUpdate::getWeekStartDate).reversed())
                    .map(this::toWeeklyView)
                    .toList();
            view.setWeeklyUpdates(new ArrayList<>(weeklyViews));
            view.setLatestUpdate(weeklyViews.isEmpty() ? null : weeklyViews.get(0));
            BuKeyMatterWeeklyUpdateView currentWeekUpdate = weeklyViews.stream()
                    .filter(update -> selectedWeek.equals(update.getWeekStartDate()))
                    .findFirst()
                    .orElse(null);
            view.setCurrentWeekUpdate(currentWeekUpdate);
            view.setCurrentWeekUpdated(currentWeekUpdate != null);
            view.setOverdue(!"已完成".equals(matter.getStatus())
                    && matter.getPlannedCompletionDate() != null
                    && matter.getPlannedCompletionDate().isBefore(today));
            views.add(view);
        }
        return views;
    }

    private Map<Long, User> loadOwners(List<BuKeyMatter> matters) {
        List<Long> ownerIds = matters.stream()
                .map(BuKeyMatter::getOwnerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        List<User> owners = userMapper.selectBatchIds(ownerIds);
        if (owners == null) {
            return Map.of();
        }
        return owners.stream().collect(Collectors.toMap(User::getId, user -> user));
    }

    private Map<Long, Project> loadProjects(List<BuKeyMatter> matters) {
        List<Long> projectIds = matters.stream()
                .map(BuKeyMatter::getProjectId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Project> result = new java.util.HashMap<>();
        List<Long> pending = new ArrayList<>(projectIds);
        while (!pending.isEmpty()) {
            List<Project> loaded = projectMapper.selectBatchIds(pending);
            if (loaded == null || loaded.isEmpty()) break;
            pending = loaded.stream()
                    .map(Project::getParentId)
                    .filter(java.util.Objects::nonNull)
                    .filter(parentId -> !result.containsKey(parentId))
                    .distinct()
                    .toList();
            loaded.forEach(project -> result.put(project.getId(), project));
        }
        return result;
    }

    private Map<Long, User> loadParticipantUsers(List<BuKeyMatterParticipant> relations) {
        List<Long> userIds = relations.stream()
                .map(BuKeyMatterParticipant::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectBatchIds(userIds);
        if (users == null) {
            return Map.of();
        }
        return users.stream().collect(Collectors.toMap(User::getId, user -> user));
    }

    private List<BuKeyMatterParticipantView> toParticipantViews(
            Long matterId,
            Map<Long, List<BuKeyMatterParticipant>> participantsByMatter,
            Map<Long, User> participantUsers) {
        List<BuKeyMatterParticipantView> views = new ArrayList<>();
        for (BuKeyMatterParticipant relation : participantsByMatter.getOrDefault(matterId, List.of())) {
            User user = participantUsers.get(relation.getUserId());
            if (user == null) {
                continue;
            }
            BuKeyMatterParticipantView view = new BuKeyMatterParticipantView();
            view.setUserId(user.getId());
            view.setUsername(user.getUsername());
            view.setRealName(user.getRealName());
            views.add(view);
        }
        return views;
    }

    private List<Long> normalizeParticipantIds(BuKeyMatterRequest request) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (request.getParticipantIds() != null) {
            request.getParticipantIds().stream()
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        ids.add(request.getOwnerId());
        return List.copyOf(ids);
    }

    private List<Long> normalizeUpdateParticipantIds(Long matterId, BuKeyMatterRequest request) {
        if (request.getParticipantIds() != null) {
            return normalizeParticipantIds(request);
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        List<BuKeyMatterParticipant> existingRelations = participantMapper.selectList(
                new LambdaQueryWrapper<BuKeyMatterParticipant>()
                        .eq(BuKeyMatterParticipant::getKeyMatterId, matterId));
        if (existingRelations != null) {
            existingRelations.stream()
                    .map(BuKeyMatterParticipant::getUserId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        ids.add(request.getOwnerId());
        return List.copyOf(ids);
    }

    private void validateParticipantIds(List<Long> participantIds, Long ownerId) {
        List<Long> nonOwnerIds = participantIds.stream()
                .filter(id -> !id.equals(ownerId))
                .distinct()
                .toList();
        if (nonOwnerIds.isEmpty()) {
            return;
        }
        List<User> users = userMapper.selectBatchIds(nonOwnerIds);
        if (users == null) {
            users = List.of();
        }
        Set<Long> activeIds = users.stream()
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .map(User::getId)
                .collect(Collectors.toSet());
        for (Long id : nonOwnerIds) {
            if (!activeIds.contains(id)) {
                throw new RuntimeException("参与人不存在或已停用");
            }
        }
    }

    private void syncParticipants(Long matterId, List<Long> participantIds) {
        participantMapper.delete(new LambdaQueryWrapper<BuKeyMatterParticipant>()
                .eq(BuKeyMatterParticipant::getKeyMatterId, matterId));
        for (Long userId : participantIds) {
            BuKeyMatterParticipant participant = new BuKeyMatterParticipant();
            participant.setKeyMatterId(matterId);
            participant.setUserId(userId);
            participantMapper.insert(participant);
        }
    }

    private BuKeyMatterView toView(BuKeyMatter matter) {
        BuKeyMatterView view = new BuKeyMatterView();
        view.setId(matter.getId());
        view.setTitle(matter.getTitle());
        view.setDescription(matter.getDescription());
        view.setProjectId(matter.getProjectId());
        view.setOwnerId(matter.getOwnerId());
        view.setPriority(matter.getPriority());
        view.setStatus(matter.getStatus());
        view.setProgress(matter.getProgress());
        view.setStartDate(matter.getStartDate());
        view.setPlannedCompletionDate(matter.getPlannedCompletionDate());
        view.setCompletedAt(matter.getCompletedAt());
        view.setSortOrder(matter.getSortOrder());
        view.setCreatedAt(matter.getCreatedAt());
        view.setUpdatedAt(matter.getUpdatedAt());
        return view;
    }

    private BuKeyMatterWeeklyUpdateView toWeeklyView(BuKeyMatterWeeklyUpdate update) {
        BuKeyMatterWeeklyUpdateView view = new BuKeyMatterWeeklyUpdateView();
        view.setId(update.getId());
        view.setWeekStartDate(update.getWeekStartDate());
        view.setStatus(update.getStatus());
        view.setProgress(update.getProgress());
        view.setProgressSummary(update.getProgressSummary());
        view.setIssues(update.getIssues());
        view.setNextWeekPlan(update.getNextWeekPlan());
        view.setSupportNeeded(update.getSupportNeeded());
        view.setCreatedBy(update.getCreatedBy());
        view.setCreatedAt(update.getCreatedAt());
        view.setUpdatedAt(update.getUpdatedAt());
        return view;
    }

    private void synchronizeMatter(BuKeyMatter matter, String status, int progress, LocalDateTime now) {
        boolean alreadyCompleted = "已完成".equals(matter.getStatus());
        matter.setStatus(status);
        matter.setProgress(progress);
        if ("已完成".equals(status)) {
            if (!alreadyCompleted || matter.getCompletedAt() == null) {
                matter.setCompletedAt(now);
            }
        } else {
            matter.setCompletedAt(null);
        }
        matter.setUpdatedAt(now);
    }

    private void validateWeekStart(LocalDate weekStartDate) {
        if (weekStartDate == null || weekStartDate.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new RuntimeException("周进展日期必须为周一");
        }
    }

    private boolean completedDuring(BuKeyMatter matter, LocalDate start, LocalDate end) {
        if (matter.getCompletedAt() == null) {
            return false;
        }
        LocalDate completedDate = matter.getCompletedAt().toLocalDate();
        return !completedDate.isBefore(start) && !completedDate.isAfter(end);
    }

    private int priorityRank(String priority) {
        return switch (priority) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            default -> 3;
        };
    }

    private int riskRank(String status) {
        return switch (status) {
            case "已阻塞" -> 0;
            case "有风险" -> 1;
            default -> 2;
        };
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private LocalDate currentWeekStart() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private record NormalizedMatter(String priority, String status, int progress) {
    }

    private record NormalizedWeeklyUpdate(String status, int progress) {
    }
}
