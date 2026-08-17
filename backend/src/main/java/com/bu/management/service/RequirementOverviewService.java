package com.bu.management.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoWorkitemLink;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoWorkitemLinkMapper;
import com.bu.management.vo.WorkItemOverviewItem;
import com.bu.management.vo.WorkItemOverviewQuery;
import com.bu.management.vo.WorkItemOverviewResponse;
import com.bu.management.vo.WorkItemOverviewSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RequirementOverviewService {

    private final RequirementService requirementService;
    private final YunxiaoWorkItemQueryService yunxiaoQueryService;
    private final YunxiaoWorkitemLinkMapper workitemLinkMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    public WorkItemOverviewResponse getOverview(Long userId, String role, WorkItemOverviewQuery query) {
        int pageNumber = Math.max(1, query.getPage());
        int pageSize = Math.min(200, Math.max(1, query.getSize()));
        Page<Requirement> localPage = requirementService.listWithPermission(
                userId, role, 1, 10000, query.getBusinessLineId(), query.getProjectId(),
                query.getType(), null, query.getPriority(), query.getKeyword());
        List<Requirement> localRequirements = localPage.getRecords() == null ? List.of() : localPage.getRecords();
        Map<Long, Project> projects = loadProjects(localRequirements);
        Map<Long, User> users = loadUsers(localRequirements);

        List<WorkItemOverviewItem> cloudItems = yunxiaoQueryService.listCloudItems(
                YunxiaoWorkItemConstants.CATEGORY_REQUIREMENT, userId, role,
                query.getProjectId(), query.getAssigneeId(), query.getNormalizedStatus(), query.getKeyword());
        Map<String, WorkItemOverviewItem> cloudById = cloudItems.stream()
                .collect(Collectors.toMap(WorkItemOverviewItem::getYunxiaoWorkitemId,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, YunxiaoWorkitemLink> linksByRequirement = loadLinks();
        Set<String> linkedCloudIds = linksByRequirement.values().stream()
                .map(YunxiaoWorkitemLink::getYunxiaoWorkitemId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<WorkItemOverviewItem> merged = new ArrayList<>();
        if (!"YUNXIAO".equalsIgnoreCase(query.getDataSource())) {
            localRequirements.stream().map(requirement -> toLocalItem(
                            requirement, projects, users, linksByRequirement.get(requirement.getId()), cloudById))
                    .filter(item -> matchesLocalFilters(item, query))
                    .forEach(merged::add);
        }
        if (!"LOCAL".equalsIgnoreCase(query.getDataSource())) {
            cloudItems.stream()
                    .filter(item -> !linkedCloudIds.contains(item.getYunxiaoWorkitemId()))
                    .forEach(merged::add);
        }
        merged.sort(Comparator.comparing(WorkItemOverviewItem::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return page(merged, pageNumber, pageSize);
    }

    private WorkItemOverviewItem toLocalItem(
            Requirement requirement, Map<Long, Project> projects, Map<Long, User> users,
            YunxiaoWorkitemLink link, Map<String, WorkItemOverviewItem> cloudById) {
        WorkItemOverviewItem item = new WorkItemOverviewItem();
        item.setRecordKey("local:" + requirement.getId());
        item.setDataSource("LOCAL");
        item.setReadOnly(false);
        item.setId(requirement.getId());
        item.setRequirementId(requirement.getId());
        item.setRequirementNo(requirement.getReqNo());
        item.setTitle(requirement.getTitle());
        item.setDescription(requirement.getDescription());
        item.setBusinessLineId(requirement.getBusinessLineId());
        item.setProjectId(requirement.getProjectId());
        if (requirement.getProjectId() != null) {
            item.setProjectIds(List.of(requirement.getProjectId()));
            Project project = projects.get(requirement.getProjectId());
            if (project != null) {
                item.setProjectName(project.getName());
                item.setProjectNames(List.of(project.getName()));
                item.setProjectFullPath(project.getFullPath());
            }
        }
        item.setType(requirement.getType());
        item.setStatus(requirement.getStatus());
        item.setNormalizedStatus(YunxiaoWorkItemConstants.normalizeStatus(null, requirement.getStatus()));
        item.setPriority(requirement.getPriority());
        item.setBusinessSource(requirement.getSource());
        item.setCustomerContactId(requirement.getCustomerContactId());
        item.setExpectedOnlineDate(requirement.getExpectedOnlineDate());
        item.setEstimatedOnlineDate(requirement.getEstimatedOnlineDate());
        item.setActualOnlineDate(requirement.getActualOnlineDate());
        LocalDate dueDate = requirement.getEstimatedOnlineDate() == null
                ? requirement.getExpectedOnlineDate() : requirement.getEstimatedOnlineDate();
        item.setDueDate(dueDate);
        applyOverdueState(item);
        item.setCreatorId(requirement.getCreatorId());
        item.setAssigneeId(requirement.getCreatorId());
        User creator = requirement.getCreatorId() == null ? null : users.get(requirement.getCreatorId());
        if (creator != null) {
            item.setAssigneeName(creator.getRealName());
            item.setAssigneeUsername(creator.getUsername());
        }
        item.setCreatedAt(requirement.getCreatedAt());
        item.setUpdatedAt(requirement.getUpdatedAt());
        if (link != null) {
            item.setLinkedYunxiaoWorkitemId(link.getYunxiaoWorkitemId());
            item.setLinkedYunxiaoSerialNumber(link.getSerialNumber());
            WorkItemOverviewItem cloud = cloudById.get(link.getYunxiaoWorkitemId());
            if (cloud != null) {
                item.setLinkedYunxiaoStatus(cloud.getStatus());
                item.setLinkedYunxiaoLastSyncedAt(cloud.getLastSyncedAt());
            }
        }
        return item;
    }

    private void applyOverdueState(WorkItemOverviewItem item) {
        boolean overdue = item.getDueDate() != null
                && item.getDueDate().isBefore(LocalDate.now())
                && !YunxiaoWorkItemConstants.STATUS_COMPLETED.equals(item.getNormalizedStatus());
        item.setOverdueIncomplete(overdue);
        item.setOverdueDays(overdue ? ChronoUnit.DAYS.between(item.getDueDate(), LocalDate.now()) : null);
    }

    private boolean matchesLocalFilters(WorkItemOverviewItem item, WorkItemOverviewQuery query) {
        if (query.getAssigneeId() != null && !query.getAssigneeId().equals(item.getAssigneeId())) {
            return false;
        }
        if (StringUtils.hasText(query.getNormalizedStatus())
                && !query.getNormalizedStatus().equalsIgnoreCase(item.getNormalizedStatus())) {
            return false;
        }
        if (!StringUtils.hasText(query.getKeyword())) {
            return true;
        }
        String keyword = query.getKeyword().trim().toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.of(
                        item.getTitle(), item.getRequirementNo(), item.getProjectName(), item.getAssigneeName())
                .filter(StringUtils::hasText)
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains(keyword));
    }

    private Map<Long, YunxiaoWorkitemLink> loadLinks() {
        List<YunxiaoWorkitemLink> links = workitemLinkMapper.selectList(null);
        if (links == null) {
            return Map.of();
        }
        return links.stream().collect(Collectors.toMap(YunxiaoWorkitemLink::getRequirementId,
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private Map<Long, Project> loadProjects(List<Requirement> requirements) {
        Set<Long> ids = requirements.stream().map(Requirement::getProjectId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ids.isEmpty() ? Map.of() : mapById(projectMapper.selectBatchIds(ids), Project::getId);
    }

    private Map<Long, User> loadUsers(List<Requirement> requirements) {
        Set<Long> ids = requirements.stream().map(Requirement::getCreatorId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return ids.isEmpty() ? Map.of() : mapById(userMapper.selectBatchIds(ids), User::getId);
    }

    private <T> Map<Long, T> mapById(List<T> values, Function<T, Long> idGetter) {
        if (values == null) {
            return Map.of();
        }
        return values.stream().collect(Collectors.toMap(idGetter, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    public static WorkItemOverviewResponse page(List<WorkItemOverviewItem> items, int page, int size) {
        WorkItemOverviewResponse response = new WorkItemOverviewResponse();
        response.setTotal(items.size());
        response.setCurrent(page);
        response.setSize(size);
        int from = Math.min(items.size(), (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        response.setRecords(new ArrayList<>(items.subList(from, to)));
        response.setSummary(summary(items));
        response.setAnalysis(WorkItemAnalysisBuilder.build(items));
        response.setLastSyncedAt(items.stream().map(WorkItemOverviewItem::getLastSyncedAt)
                .filter(java.util.Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        return response;
    }

    private static WorkItemOverviewSummary summary(List<WorkItemOverviewItem> items) {
        WorkItemOverviewSummary summary = new WorkItemOverviewSummary();
        summary.setTotalCount(items.size());
        summary.setLocalCount(items.stream().filter(item -> "LOCAL".equals(item.getDataSource())).count());
        summary.setYunxiaoCount(items.stream().filter(item -> "YUNXIAO".equals(item.getDataSource())).count());
        summary.setPendingCount(count(items, YunxiaoWorkItemConstants.STATUS_PENDING));
        summary.setInProgressCount(count(items, YunxiaoWorkItemConstants.STATUS_IN_PROGRESS));
        summary.setCompletedCount(count(items, YunxiaoWorkItemConstants.STATUS_COMPLETED));
        summary.setOtherCount(count(items, YunxiaoWorkItemConstants.STATUS_OTHER));
        return summary;
    }

    private static long count(List<WorkItemOverviewItem> items, String status) {
        return items.stream().filter(item -> status.equals(item.getNormalizedStatus())).count();
    }
}
