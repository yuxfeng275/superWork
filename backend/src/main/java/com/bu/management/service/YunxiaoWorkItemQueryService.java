package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.bu.management.vo.WorkItemOverviewItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
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
public class YunxiaoWorkItemQueryService {

    private final YunxiaoWorkitemCacheMapper cacheMapper;
    private final YunxiaoProjectMappingMapper projectMappingMapper;
    private final YunxiaoUserMappingMapper userMappingMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final DataPermissionService dataPermissionService;
    private final ObjectMapper objectMapper;

    public List<WorkItemOverviewItem> listCloudItems(
            String category, Long userId, String role, Long projectId, Long assigneeId,
            String normalizedStatus, String keyword) {
        List<YunxiaoProjectMapping> mappings = projectMappingMapper.selectList(
                new LambdaQueryWrapper<YunxiaoProjectMapping>()
                        .eq(YunxiaoProjectMapping::getSyncEnabled, 1));
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }

        Set<Long> permittedProjectIds = permittedProjectIds(userId, role, mappings);
        Map<String, List<Long>> projectIdsBySpace = mappings.stream()
                .filter(mapping -> permittedProjectIds.contains(mapping.getProjectId()))
                .collect(Collectors.groupingBy(
                        YunxiaoProjectMapping::getYunxiaoProjectId,
                        LinkedHashMap::new,
                        Collectors.mapping(YunxiaoProjectMapping::getProjectId,
                                Collectors.toCollection(LinkedHashSet::new))))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new ArrayList<>(entry.getValue()),
                        (left, right) -> left, LinkedHashMap::new));
        if (projectIdsBySpace.isEmpty()) {
            return List.of();
        }

        Set<Long> mappedProjectIds = projectIdsBySpace.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Project> projects = mapById(projectMapper.selectBatchIds(mappedProjectIds), Project::getId);
        Map<String, Long> localUserIdByCloudId = loadUserMappings();
        Map<Long, User> users = localUserIdByCloudId.isEmpty()
                ? Map.of()
                : mapById(userMapper.selectBatchIds(new LinkedHashSet<>(localUserIdByCloudId.values())), User::getId);

        List<YunxiaoWorkitemCache> caches = cacheMapper.selectList(
                new LambdaQueryWrapper<YunxiaoWorkitemCache>()
                        .eq(YunxiaoWorkitemCache::getCategory, category)
                        .eq(YunxiaoWorkitemCache::getActive, 1));
        if (caches == null) {
            return List.of();
        }
        return caches.stream()
                .filter(cache -> category.equalsIgnoreCase(cache.getCategory()))
                .filter(cache -> Integer.valueOf(1).equals(cache.getActive()))
                .map(cache -> toView(cache, projectIdsBySpace, projects, localUserIdByCloudId, users))
                .filter(item -> !item.getProjectIds().isEmpty())
                .filter(item -> projectId == null || item.getProjectIds().contains(projectId))
                .filter(item -> assigneeId == null || assigneeId.equals(item.getAssigneeId()))
                .filter(item -> !StringUtils.hasText(normalizedStatus)
                        || normalizedStatus.equalsIgnoreCase(item.getNormalizedStatus()))
                .filter(item -> matchesKeyword(item, keyword))
                .sorted(Comparator.comparing(WorkItemOverviewItem::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public WorkItemOverviewItem getCloudItem(String workitemId, Long userId, String role) {
        for (String category : List.of("Req", "Task", "Bug")) {
            WorkItemOverviewItem match = listCloudItems(
                    category, userId, role, null, null, null, null).stream()
                    .filter(item -> workitemId.equals(item.getYunxiaoWorkitemId()))
                    .findFirst().orElse(null);
            if (match != null) {
                return match;
            }
        }
        throw new RuntimeException("云效工作项不存在或无权查看");
    }

    private WorkItemOverviewItem toView(
            YunxiaoWorkitemCache cache,
            Map<String, List<Long>> projectIdsBySpace,
            Map<Long, Project> projects,
            Map<String, Long> localUserIdByCloudId,
            Map<Long, User> users) {
        WorkItemOverviewItem item = new WorkItemOverviewItem();
        item.setRecordKey("yunxiao:" + cache.getYunxiaoWorkitemId());
        item.setDataSource("YUNXIAO");
        item.setReadOnly(true);
        item.setYunxiaoWorkitemId(cache.getYunxiaoWorkitemId());
        item.setSerialNumber(cache.getSerialNumber());
        item.setCategory(cache.getCategory());
        item.setTitle(cache.getTitle());
        item.setDescription(extractDescription(cache.getRawJson()));
        List<Long> projectIds = projectIdsBySpace.getOrDefault(cache.getYunxiaoProjectId(), List.of());
        item.setProjectIds(projectIds);
        item.setProjectNames(projectIds.stream().map(projects::get).filter(java.util.Objects::nonNull)
                .map(Project::getName).toList());
        if (!projectIds.isEmpty()) {
            Project primary = projects.get(projectIds.get(0));
            item.setProjectId(projectIds.get(0));
            item.setProjectName(primary == null ? null : primary.getName());
            item.setProjectFullPath(primary == null ? null : primary.getFullPath());
        }
        Long localUserId = localUserIdByCloudId.get(cache.getYunxiaoAssigneeId());
        User user = localUserId == null ? null : users.get(localUserId);
        item.setAssigneeId(localUserId);
        item.setAssigneeKey(localUserId == null
                ? "yunxiao:" + cache.getYunxiaoAssigneeId()
                : "local:" + localUserId);
        item.setAssigneeName(StringUtils.hasText(cache.getAssigneeName())
                ? cache.getAssigneeName() : user == null ? null : user.getRealName());
        item.setAssigneeUsername(user == null ? null : user.getUsername());
        item.setStatus(cache.getStatus());
        item.setNormalizedStatus(cache.getNormalizedStatus());
        item.setEstimatedHours(cache.getEstimatedHours());
        item.setActualHours(cache.getActualHours());
        item.setCreatedAt(cache.getSourceCreatedAt() == null ? cache.getCreatedAt() : cache.getSourceCreatedAt());
        item.setUpdatedAt(cache.getSourceUpdatedAt() == null ? cache.getUpdatedAt() : cache.getSourceUpdatedAt());
        item.setLastSyncedAt(cache.getLastSyncedAt());
        item.setDueDate(cache.getDueDate());
        applyOverdueState(item);
        return item;
    }

    private void applyOverdueState(WorkItemOverviewItem item) {
        boolean overdue = item.getDueDate() != null
                && item.getDueDate().isBefore(LocalDate.now())
                && !"COMPLETED".equals(item.getNormalizedStatus());
        item.setOverdueIncomplete(overdue);
        item.setOverdueDays(overdue ? ChronoUnit.DAYS.between(item.getDueDate(), LocalDate.now()) : null);
    }

    private Set<Long> permittedProjectIds(Long userId, String role, List<YunxiaoProjectMapping> mappings) {
        Set<Long> all = mappings.stream().map(YunxiaoProjectMapping::getProjectId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (dataPermissionService.isBuAdmin(role) || !dataPermissionService.isProjectRole(role)) {
            return all;
        }
        return new LinkedHashSet<>(dataPermissionService.getUserProjectIds(userId));
    }

    private Map<String, Long> loadUserMappings() {
        List<YunxiaoUserMapping> mappings = userMappingMapper.selectList(
                new LambdaQueryWrapper<YunxiaoUserMapping>()
                        .eq(YunxiaoUserMapping::getSyncEnabled, 1));
        if (mappings == null) {
            return Map.of();
        }
        return mappings.stream().filter(mapping -> StringUtils.hasText(mapping.getYunxiaoUserId()))
                .collect(Collectors.toMap(YunxiaoUserMapping::getYunxiaoUserId,
                        YunxiaoUserMapping::getUserId, (left, right) -> left, LinkedHashMap::new));
    }

    private boolean matchesKeyword(WorkItemOverviewItem item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String value = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> fields = new ArrayList<>(List.of(
                nullToEmpty(item.getTitle()), nullToEmpty(item.getSerialNumber()),
                nullToEmpty(item.getStatus()), nullToEmpty(item.getAssigneeName())));
        fields.addAll(item.getProjectNames());
        return fields.stream().map(field -> field.toLowerCase(Locale.ROOT)).anyMatch(field -> field.contains(value));
    }

    private String extractDescription(String rawJson) {
        if (!StringUtils.hasText(rawJson) || objectMapper == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            String description = node.path("description").asText(null);
            return StringUtils.hasText(description) ? description : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private <T> Map<Long, T> mapById(List<T> values, Function<T, Long> idGetter) {
        if (values == null) {
            return Map.of();
        }
        return values.stream().collect(Collectors.toMap(idGetter, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
