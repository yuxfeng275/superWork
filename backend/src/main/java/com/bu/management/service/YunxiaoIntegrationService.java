package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.bu.management.config.YunxiaoRuntimeConfig;
import com.bu.management.constant.YunxiaoWorkItemConstants;
import com.bu.management.dto.YunxiaoConfigRequest;
import com.bu.management.dto.YunxiaoProjectMappingRequest;
import com.bu.management.dto.YunxiaoUserMappingRequest;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoEffortRecord;
import com.bu.management.entity.YunxiaoEstimatedEffort;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.integration.YunxiaoClient;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoEstimatedEffortMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import com.bu.management.vo.BuDashboardResponse;
import com.bu.management.vo.YunxiaoConnectionTestResponse;
import com.bu.management.vo.YunxiaoMemberOption;
import com.bu.management.vo.YunxiaoProjectOption;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class YunxiaoIntegrationService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long EPOCH_MILLI_THRESHOLD = 100_000_000_000L;
    private static final String EXECUTION_CATEGORIES = YunxiaoWorkItemConstants.EXECUTION_CATEGORIES;

    private final YunxiaoConfigService configService;
    private final YunxiaoClient client;
    private final YunxiaoProjectMappingMapper projectMappingMapper;
    private final YunxiaoUserMappingMapper userMappingMapper;
    private final YunxiaoWorkitemCacheMapper workitemCacheMapper;
    private final YunxiaoEffortRecordMapper effortRecordMapper;
    private final YunxiaoEstimatedEffortMapper estimatedEffortMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    private final AtomicBoolean asyncSyncRunning = new AtomicBoolean(false);
    private volatile Map<String, Object> asyncSyncStatus = Map.of("status", "IDLE");

    public BuDashboardResponse.IntegrationStatus getStatus() {
        YunxiaoRuntimeConfig config = configService.getRuntimeConfig();
        BuDashboardResponse.IntegrationStatus status = new BuDashboardResponse.IntegrationStatus();
        status.setEnabled(config.enabled());
        status.setConfigured(config.isConfigured());
        status.setEdition(config.edition());
        status.setBaseUrl(config.baseUrl());
        status.setOrganizationId(config.organizationId());
        status.setTokenConfigured(StringUtils.hasText(config.token()));
        status.setTokenSource(config.tokenSource());
        status.setOrganizationConfigured(config.isRegionEdition()
                || StringUtils.hasText(config.organizationId()));
        status.setLastTestedAt(config.lastTestedAt());
        status.setLastTestStatus(config.lastTestStatus());
        status.setLastTestMessage(config.lastTestMessage());
        status.setMappedProjects(projectMappingMapper.selectCount(null));
        status.setMappedUsers(userMappingMapper.selectCount(null));

        List<YunxiaoProjectMapping> mappings = listProjectMappings();
        mappings.stream()
                .filter(item -> "SUCCESS".equals(item.getLastSyncStatus()) && item.getLastSyncedAt() != null)
                .map(YunxiaoProjectMapping::getLastSyncedAt)
                .max(LocalDateTime::compareTo)
                .ifPresent(status::setLastSuccessfulSync);
        mappings.stream()
                .filter(item -> StringUtils.hasText(item.getLastSyncError()))
                .findFirst()
                .ifPresent(item -> status.setLastError(item.getLastSyncError()));
        return status;
    }

    public BuDashboardResponse.IntegrationStatus saveConfig(
            YunxiaoConfigRequest request, Long userId) {
        configService.save(request, userId);
        return getStatus();
    }

    public YunxiaoConnectionTestResponse testConnection() {
        LocalDateTime testedAt = LocalDateTime.now();
        YunxiaoConnectionTestResponse result = new YunxiaoConnectionTestResponse();
        result.setTestedAt(testedAt);
        try {
            JsonNode response = client.getCurrentUser();
            JsonNode user = response.has("data") && response.path("data").isObject()
                    ? response.path("data")
                    : response;
            result.setSuccess(true);
            result.setUserId(text(user, "id"));
            result.setUserName(defaultText(user, "name", "已认证用户"));
            result.setEmail(text(user, "email"));
            result.setMessage("连接成功");
        } catch (RuntimeException ex) {
            result.setSuccess(false);
            result.setMessage(limitMessage(ex.getMessage()));
        }
        configService.recordConnectionTest(result.isSuccess(), result.getMessage(), testedAt);
        return result;
    }

    public List<YunxiaoProjectMapping> listProjectMappings() {
        return projectMappingMapper.selectList(new LambdaQueryWrapper<YunxiaoProjectMapping>()
                .orderByAsc(YunxiaoProjectMapping::getProjectId));
    }

    public Map<String, Object> analysis() {
        List<YunxiaoWorkitemCache> items = workitemCacheMapper.selectList(null);
        long requirements = items.stream().filter(item -> "Req".equalsIgnoreCase(item.getCategory())).count();
        long tasks = items.stream().filter(item -> !"Req".equalsIgnoreCase(item.getCategory())).count();
        long delayed = items.stream().filter(this::isDelayed).count();
        Map<String, Map<String, Object>> byOwner = new LinkedHashMap<>();
        items.forEach(item -> {
            String owner = StringUtils.hasText(item.getAssigneeName()) ? item.getAssigneeName() : "未分配";
            Map<String, Object> row = byOwner.computeIfAbsent(owner, key -> new LinkedHashMap<>(Map.of("name", key, "count", 0, "delayed", 0, "actualHours", BigDecimal.ZERO)));
            row.put("count", ((Integer) row.get("count")) + 1);
            row.put("delayed", ((Integer) row.get("delayed")) + (isDelayed(item) ? 1 : 0));
            row.put("actualHours", ((BigDecimal) row.get("actualHours")).add(item.getActualHours() == null ? BigDecimal.ZERO : item.getActualHours()));
        });
        return new LinkedHashMap<>(Map.of("total", items.size(), "requirements", requirements, "tasks", tasks, "delayed", delayed, "byOwner", new ArrayList<>(byOwner.values())));
    }

    private boolean isDelayed(YunxiaoWorkitemCache item) {
        if (item.getRawJson() == null || "已完成".equals(item.getStatus()) || "COMPLETED".equalsIgnoreCase(item.getStatus())) return false;
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.getRawJson());
            String due = firstText(node, "dueDate", "gmtDue", "planEndTime", "plannedEndTime");
            return due != null && due.length() >= 10 && LocalDate.parse(due.substring(0, 10)).isBefore(LocalDate.now(BUSINESS_ZONE));
        } catch (Exception ignored) { return false; }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && (value.isTextual() || value.isNumber())) {
                return value.asText();
            }
        }
        return null;
    }

    public List<YunxiaoProjectOption> listProjects() {
        Set<String> seenIds = new HashSet<>();
        return client.searchProjects().stream()
                .map(node -> {
                    String id = text(node, "id");
                    String customCode = text(node, "customCode");
                    return new YunxiaoProjectOption(
                            id,
                            defaultText(node, "name", StringUtils.hasText(customCode) ? customCode : id),
                            customCode,
                            text(node, "status")
                    );
                })
                .filter(project -> StringUtils.hasText(project.id()))
                .filter(project -> seenIds.add(project.id()))
                .sorted(Comparator.comparing(
                        YunxiaoProjectOption::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public List<YunxiaoMemberOption> listMembers() {
        Set<String> seenUserIds = new HashSet<>();
        return client.searchMembers().stream()
                .map(node -> {
                    String userId = text(node, "userId");
                    String email = text(node, "email");
                    return new YunxiaoMemberOption(
                            userId,
                            text(node, "id"),
                            defaultText(node, "name", StringUtils.hasText(email) ? email : userId),
                            email,
                            text(node, "status")
                    );
                })
                .filter(member -> StringUtils.hasText(member.userId()))
                .filter(member -> seenUserIds.add(member.userId()))
                .sorted(Comparator.comparing(
                        YunxiaoMemberOption::name,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public List<YunxiaoUserMapping> listUserMappings() {
        return userMappingMapper.selectList(new LambdaQueryWrapper<YunxiaoUserMapping>()
                .orderByAsc(YunxiaoUserMapping::getUserId));
    }

    @Transactional
    public YunxiaoProjectMapping saveProjectMapping(YunxiaoProjectMappingRequest request) {
        if (request.getProjectId() == null || !StringUtils.hasText(request.getYunxiaoProjectId())) {
            throw new RuntimeException("本地项目和云效项目ID不能为空");
        }
        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new RuntimeException("本地项目不存在");
        }
        YunxiaoProjectMapping mapping = projectMappingMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoProjectMapping>()
                        .eq(YunxiaoProjectMapping::getProjectId, request.getProjectId()));
        String yunxiaoProjectId = request.getYunxiaoProjectId().trim();
        boolean cloudProjectChanged = mapping != null
                && !yunxiaoProjectId.equals(mapping.getYunxiaoProjectId());
        if (mapping == null) {
            mapping = new YunxiaoProjectMapping();
            mapping.setCreatedAt(LocalDateTime.now());
        }
        mapping.setProjectId(request.getProjectId());
        mapping.setYunxiaoProjectId(yunxiaoProjectId);
        mapping.setWorkitemTypeId(request.getWorkitemTypeId());
        mapping.setCategory(StringUtils.hasText(request.getCategory())
                ? request.getCategory() : EXECUTION_CATEGORIES);
        mapping.setSyncEnabled(request.getSyncEnabled() == null ? 1 : request.getSyncEnabled());
        mapping.setUpdatedAt(LocalDateTime.now());
        if (mapping.getId() == null) {
            projectMappingMapper.insert(mapping);
        } else {
            projectMappingMapper.updateById(mapping);
        }
        if (cloudProjectChanged) {
            clearDerivedProjectData(mapping.getProjectId());
            mapping.setLastSyncedAt(null);
            mapping.setFullSyncedAt(null);
            mapping.setLastSyncStatus(null);
            mapping.setLastSyncError(null);
            projectMappingMapper.update(null, new UpdateWrapper<YunxiaoProjectMapping>()
                    .eq("id", mapping.getId())
                    .set("last_synced_at", null)
                    .set("full_synced_at", null)
                    .set("last_sync_status", null)
                    .set("last_sync_error", null));
        }
        return mapping;
    }

    @Transactional
    public YunxiaoUserMapping saveUserMapping(YunxiaoUserMappingRequest request) {
        if (request.getUserId() == null || !StringUtils.hasText(request.getYunxiaoUserId())) {
            throw new RuntimeException("本地用户和云效用户ID不能为空");
        }
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new RuntimeException("本地用户不存在");
        }
        YunxiaoUserMapping mapping = userMappingMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoUserMapping>()
                        .eq(YunxiaoUserMapping::getUserId, request.getUserId()));
        if (mapping == null) {
            mapping = new YunxiaoUserMapping();
            mapping.setCreatedAt(LocalDateTime.now());
        }
        mapping.setUserId(request.getUserId());
        mapping.setYunxiaoUserId(request.getYunxiaoUserId().trim());
        mapping.setSyncEnabled(request.getSyncEnabled() == null ? 1 : request.getSyncEnabled());
        mapping.setUpdatedAt(LocalDateTime.now());
        if (mapping.getId() == null) {
            userMappingMapper.insert(mapping);
        } else {
            userMappingMapper.updateById(mapping);
        }
        return mapping;
    }

    public void deleteProjectMapping(Long id) {
        projectMappingMapper.deleteById(id);
    }

    public void deleteUserMapping(Long id) {
        userMappingMapper.deleteById(id);
    }

    public synchronized List<String> syncAll() {
        if (!configService.getRuntimeConfig().isConfigured()) {
            throw new RuntimeException("请先在云效配置页面完成连接配置并启用集成");
        }
        List<String> results = new ArrayList<>();
        Map<String, List<YunxiaoProjectMapping>> mappingsByCloudProject = new LinkedHashMap<>();
        listProjectMappings().stream()
                .filter(item -> Integer.valueOf(1).equals(item.getSyncEnabled()))
                .forEach(mapping -> mappingsByCloudProject
                        .computeIfAbsent(mapping.getYunxiaoProjectId(), ignored -> new ArrayList<>())
                        .add(mapping));
        mappingsByCloudProject.values().forEach(mappings -> {
            YunxiaoProjectMapping syncOwner = mappings.get(0);
            boolean fullSync = syncOwner.getFullSyncedAt() == null;
            try {
                int count = syncProject(syncOwner);
                mappings.forEach(mapping -> {
                    markSyncSucceeded(mapping, fullSync);
                    results.add(mapping.getProjectId() + ":SUCCESS:" + count);
                });
            } catch (Exception ex) {
                mappings.forEach(mapping -> {
                    markSyncFailed(mapping, ex);
                    results.add(mapping.getProjectId() + ":FAILED:" + ex.getMessage());
                });
            }
        });
        return results;
    }

    public Map<String, Object> startAsyncSync() {
        if (!asyncSyncRunning.compareAndSet(false, true)) {
            return asyncSyncStatus;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        asyncSyncStatus = Map.of("status", "RUNNING", "startedAt", startedAt);
        CompletableFuture.runAsync(() -> {
            try {
                List<String> results = syncAll();
                asyncSyncStatus = Map.of(
                        "status", "SUCCESS",
                        "startedAt", startedAt,
                        "completedAt", LocalDateTime.now(),
                        "results", results);
            } catch (RuntimeException ex) {
                asyncSyncStatus = Map.of(
                        "status", "FAILED",
                        "startedAt", startedAt,
                        "completedAt", LocalDateTime.now(),
                        "message", limitMessage(ex.getMessage()));
            } finally {
                asyncSyncRunning.set(false);
            }
        });
        return asyncSyncStatus;
    }

    public Map<String, Object> getAsyncSyncStatus() {
        return asyncSyncStatus;
    }

    @Scheduled(cron = "${yunxiao.sync-cron:0 15 * * * *}", zone = "Asia/Shanghai")
    public void scheduledSync() {
        if (configService.getRuntimeConfig().isConfigured()) {
            syncAll();
        }
    }

    private int syncProject(YunxiaoProjectMapping mapping) {
        boolean fullSync = mapping.getFullSyncedAt() == null;
        List<JsonNode> workitems = client.searchWorkitems(
                mapping.getYunxiaoProjectId(),
                EXECUTION_CATEGORIES,
                resolveModifiedSince(mapping)
        );
        for (JsonNode node : workitems) {
            boolean enrichEffort = !fullSync;
            syncWorkitem(mapping, node, enrichEffort);
        }
        if (fullSync) {
            reconcileFullSync(mapping.getYunxiaoProjectId(), workitems);
        }
        return workitems.size();
    }

    private void reconcileFullSync(String yunxiaoProjectId, List<JsonNode> workitems) {
        Set<String> activeIds = externalIds(workitems);
        UpdateWrapper<YunxiaoWorkitemCache> wrapper = new UpdateWrapper<YunxiaoWorkitemCache>()
                .eq("yunxiao_project_id", yunxiaoProjectId)
                .eq("active", 1)
                .set("active", 0)
                .set("updated_at", LocalDateTime.now());
        if (!activeIds.isEmpty()) {
            wrapper.notIn("yunxiao_workitem_id", activeIds);
        }
        workitemCacheMapper.update(null, wrapper);
    }

    private LocalDate resolveModifiedSince(YunxiaoProjectMapping mapping) {
        if (mapping.getFullSyncedAt() == null) {
            return null;
        }
        if (mapping.getLastSyncedAt() == null) {
            return mapping.getFullSyncedAt().toLocalDate().minusDays(1);
        }
        return mapping.getLastSyncedAt().toLocalDate().minusDays(1);
    }

    private void markSyncSucceeded(YunxiaoProjectMapping mapping, boolean fullSync) {
        mapping.setLastSyncedAt(LocalDateTime.now());
        if (fullSync) {
            mapping.setFullSyncedAt(mapping.getLastSyncedAt());
        }
        mapping.setLastSyncStatus("SUCCESS");
        mapping.setLastSyncError(null);
        mapping.setUpdatedAt(LocalDateTime.now());
        projectMappingMapper.update(null, new UpdateWrapper<YunxiaoProjectMapping>()
                .eq("id", mapping.getId())
                .set("last_synced_at", mapping.getLastSyncedAt())
                .set("full_synced_at", mapping.getFullSyncedAt())
                .set("last_sync_status", mapping.getLastSyncStatus())
                .set("last_sync_error", null)
                .set("updated_at", mapping.getUpdatedAt()));
    }

    private void syncWorkitem(YunxiaoProjectMapping mapping, JsonNode node, boolean enrichEffort) {
        String workitemId = text(node, "id");
        if (!StringUtils.hasText(workitemId)) {
            return;
        }
        List<JsonNode> estimatedEfforts = enrichEffort
                ? client.listEstimatedEfforts(workitemId) : List.of();
        List<JsonNode> actualEfforts = enrichEffort
                ? client.listEffortRecords(workitemId) : List.of();
        BigDecimal estimatedHours = estimatedEfforts.stream()
                .map(item -> decimal(item, "spentTime"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actualHours = actualEfforts.stream()
                .map(item -> decimal(item, "actualTime"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        YunxiaoWorkitemCache cache = workitemCacheMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoWorkitemCache>()
                        .eq(YunxiaoWorkitemCache::getYunxiaoWorkitemId, workitemId));
        if (cache == null) {
            cache = new YunxiaoWorkitemCache();
            cache.setYunxiaoWorkitemId(workitemId);
            cache.setCreatedAt(LocalDateTime.now());
        }
        cache.setProjectId(mapping.getProjectId());
        cache.setYunxiaoProjectId(mapping.getYunxiaoProjectId());
        cache.setSerialNumber(text(node, "serialNumber"));
        cache.setCategory(text(node, "categoryId"));
        cache.setTitle(defaultText(node, "subject", "未命名工作项"));
        String displayStatus = nestedText(node, "status", "displayName");
        String statusType = firstText(node.path("status"), "statusType", "type", "category");
        cache.setStatus(displayStatus);
        cache.setNormalizedStatus(YunxiaoWorkItemConstants.normalizeStatus(statusType, displayStatus));
        cache.setYunxiaoAssigneeId(nestedText(node, "assignedTo", "id"));
        cache.setAssigneeName(nestedText(node, "assignedTo", "name"));
        if (enrichEffort) {
            cache.setEstimatedHours(estimatedHours);
            cache.setActualHours(actualHours);
        }
        cache.setRawJson(node.toString());
        cache.setSourceCreatedAt(parseDateTime(firstText(node, "gmtCreate", "createdAt", "gmtCreated")));
        cache.setSourceUpdatedAt(parseDateTime(firstText(node, "gmtModified", "updatedAt", "gmtUpdate")));
        cache.setDueDate(extractExpectedCompletionDate(node));
        cache.setActive(1);
        cache.setLastSyncedAt(LocalDateTime.now());
        cache.setUpdatedAt(LocalDateTime.now());
        if (cache.getId() == null) {
            workitemCacheMapper.insert(cache);
        } else {
            workitemCacheMapper.updateById(cache);
        }

        if (enrichEffort) {
            actualEfforts.forEach(item -> saveEffort(mapping.getProjectId(), workitemId, item));
            estimatedEfforts.forEach(item -> saveEstimatedEffort(mapping.getProjectId(), workitemId, item));
            removeStaleEfforts(workitemId, actualEfforts, estimatedEfforts);
        }
    }

    private void saveEffort(Long projectId, String workitemId, JsonNode node) {
        String recordId = text(node, "id");
        String userId = nestedText(node, "owner", "id");
        if (!StringUtils.hasText(recordId) || !StringUtils.hasText(userId)) {
            return;
        }
        YunxiaoEffortRecord record = effortRecordMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoEffortRecord>()
                        .eq(YunxiaoEffortRecord::getYunxiaoRecordId, recordId));
        if (record == null) {
            record = new YunxiaoEffortRecord();
            record.setYunxiaoRecordId(recordId);
            record.setCreatedAt(LocalDateTime.now());
        }
        record.setYunxiaoWorkitemId(workitemId);
        record.setProjectId(projectId);
        record.setYunxiaoUserId(userId);
        record.setUserName(nestedText(node, "owner", "name"));
        record.setWorkDate(parseDate(text(node, "gmtStart")));
        record.setActualHours(decimal(node, "actualTime"));
        record.setDescription(text(node, "description"));
        record.setLastSyncedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        if (record.getId() == null) {
            effortRecordMapper.insert(record);
        } else {
            effortRecordMapper.updateById(record);
        }
    }

    private void saveEstimatedEffort(Long projectId, String workitemId, JsonNode node) {
        String recordId = text(node, "id");
        String userId = nestedText(node, "owner", "id");
        if (!StringUtils.hasText(recordId) || !StringUtils.hasText(userId)) {
            return;
        }
        YunxiaoEstimatedEffort record = estimatedEffortMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoEstimatedEffort>()
                        .eq(YunxiaoEstimatedEffort::getYunxiaoRecordId, recordId));
        if (record == null) {
            record = new YunxiaoEstimatedEffort();
            record.setYunxiaoRecordId(recordId);
            record.setCreatedAt(LocalDateTime.now());
        }
        record.setYunxiaoWorkitemId(workitemId);
        record.setProjectId(projectId);
        record.setYunxiaoUserId(userId);
        record.setUserName(nestedText(node, "owner", "name"));
        record.setEstimatedHours(decimal(node, "spentTime"));
        record.setWorkType(text(node, "workType"));
        record.setDescription(text(node, "description"));
        record.setLastSyncedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        if (record.getId() == null) {
            estimatedEffortMapper.insert(record);
        } else {
            estimatedEffortMapper.updateById(record);
        }
    }

    private void removeStaleEfforts(String workitemId, List<JsonNode> actualEfforts,
                                    List<JsonNode> estimatedEfforts) {
        Set<String> actualIds = externalIds(actualEfforts);
        LambdaQueryWrapper<YunxiaoEffortRecord> actualWrapper = new LambdaQueryWrapper<YunxiaoEffortRecord>()
                .eq(YunxiaoEffortRecord::getYunxiaoWorkitemId, workitemId);
        if (!actualIds.isEmpty()) {
            actualWrapper.notIn(YunxiaoEffortRecord::getYunxiaoRecordId, actualIds);
        }
        effortRecordMapper.delete(actualWrapper);

        Set<String> estimatedIds = externalIds(estimatedEfforts);
        LambdaQueryWrapper<YunxiaoEstimatedEffort> estimatedWrapper =
                new LambdaQueryWrapper<YunxiaoEstimatedEffort>()
                        .eq(YunxiaoEstimatedEffort::getYunxiaoWorkitemId, workitemId);
        if (!estimatedIds.isEmpty()) {
            estimatedWrapper.notIn(YunxiaoEstimatedEffort::getYunxiaoRecordId, estimatedIds);
        }
        estimatedEffortMapper.delete(estimatedWrapper);
    }

    private void clearDerivedProjectData(Long projectId) {
        effortRecordMapper.delete(new LambdaQueryWrapper<YunxiaoEffortRecord>()
                .eq(YunxiaoEffortRecord::getProjectId, projectId));
        estimatedEffortMapper.delete(new LambdaQueryWrapper<YunxiaoEstimatedEffort>()
                .eq(YunxiaoEstimatedEffort::getProjectId, projectId));
        workitemCacheMapper.delete(new LambdaQueryWrapper<YunxiaoWorkitemCache>()
                .eq(YunxiaoWorkitemCache::getProjectId, projectId));
    }

    private Set<String> externalIds(List<JsonNode> nodes) {
        Set<String> ids = new HashSet<>();
        nodes.stream()
                .map(node -> text(node, "id"))
                .filter(StringUtils::hasText)
                .forEach(ids::add);
        return ids;
    }

    private void markSyncFailed(YunxiaoProjectMapping mapping, Exception ex) {
        mapping.setLastSyncStatus("FAILED");
        mapping.setLastSyncError(ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getMessage().substring(0, Math.min(1000, ex.getMessage().length())));
        mapping.setUpdatedAt(LocalDateTime.now());
        projectMappingMapper.updateById(mapping);
    }

    private LocalDate parseDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return LocalDate.now();
        }
        String value = raw.trim();
        if (value.matches("-?\\d+")) {
            long epochValue = Long.parseLong(value);
            Instant instant = Math.abs(epochValue) >= EPOCH_MILLI_THRESHOLD
                    ? Instant.ofEpochMilli(epochValue)
                    : Instant.ofEpochSecond(epochValue);
            return instant.atZone(BUSINESS_ZONE).toLocalDate();
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.matches("-?\\d+")) {
            long epochValue = Long.parseLong(value);
            Instant instant = Math.abs(epochValue) >= EPOCH_MILLI_THRESHOLD
                    ? Instant.ofEpochMilli(epochValue)
                    : Instant.ofEpochSecond(epochValue);
            return instant.atZone(BUSINESS_ZONE).toLocalDateTime();
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value.replace(' ', 'T'));
            } catch (DateTimeParseException invalidDateTime) {
                return null;
            }
        }
    }

    private LocalDate extractExpectedCompletionDate(JsonNode node) {
        JsonNode customFields = node.path("customFieldValues");
        if (customFields.isArray()) {
            LocalDate expectedDate = extractCustomDate(customFields, "ExpCompletionTime", "期望完成时间");
            if (expectedDate != null) {
                return expectedDate;
            }
            LocalDate plannedDate = extractCustomDate(customFields, "80", "计划完成时间");
            if (plannedDate != null) {
                return plannedDate;
            }
        }
        return parseOptionalDate(firstText(node, "dueDate", "gmtDue", "planEndTime", "plannedEndTime"));
    }

    private LocalDate extractCustomDate(JsonNode customFields, String fieldId, String fieldName) {
        for (JsonNode field : customFields) {
            if (fieldId.equals(text(field, "fieldId")) || fieldName.equals(text(field, "fieldName"))) {
                JsonNode values = field.path("values");
                if (values.isArray() && !values.isEmpty()) {
                    return parseOptionalDate(firstText(values.get(0), "identifier", "displayValue"));
                }
            }
        }
        return null;
    }

    private LocalDate parseOptionalDate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return parseDate(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String defaultText(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String nestedText(JsonNode node, String parent, String field) {
        return text(node.path(parent), field);
    }

    private String limitMessage(String message) {
        String value = StringUtils.hasText(message) ? message : "云效连接测试失败";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
