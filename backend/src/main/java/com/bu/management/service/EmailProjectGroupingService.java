package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Project;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.vo.EmailGroupingJobStatus;
import com.bu.management.vo.EmailProjectGroupView;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class EmailProjectGroupingService {
    private static final int BATCH_SIZE = 15;
    private static final double CONFIDENCE_THRESHOLD = 0.65;

    private final EmailMessageMapper messageMapper;
    private final ProjectMapper projectMapper;
    private final DeepSeekDigestClient deepSeekClient;
    private final Executor taskExecutor;
    private final Map<Long, EmailGroupingJobStatus> jobs = new ConcurrentHashMap<>();

    public EmailProjectGroupingService(
            EmailMessageMapper messageMapper,
            ProjectMapper projectMapper,
            DeepSeekDigestClient deepSeekClient,
            @Qualifier("emailTaskExecutor") Executor taskExecutor) {
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.deepSeekClient = deepSeekClient;
        this.taskExecutor = taskExecutor;
    }

    public EmailGroupingJobStatus startAsync(Long ownerUserId, boolean regroupAll) {
        EmailGroupingJobStatus current = jobs.get(ownerUserId);
        if (current != null && "RUNNING".equals(current.status())) return current;
        int total = Math.toIntExact(messageMapper.selectCount(eligibleQuery(ownerUserId, regroupAll)));
        LocalDateTime now = LocalDateTime.now();
        EmailGroupingJobStatus queued = new EmailGroupingJobStatus(
                "RUNNING", total, 0, 0, 0, "智能分组已启动", now, null);
        jobs.put(ownerUserId, queued);
        taskExecutor.execute(() -> execute(ownerUserId, regroupAll, now));
        return queued;
    }

    public EmailGroupingJobStatus status(Long ownerUserId) {
        return jobs.getOrDefault(ownerUserId, new EmailGroupingJobStatus(
                "IDLE", 0, 0, 0, 0, null, null, null));
    }

    public List<EmailProjectGroupView> groups(Long ownerUserId) {
        List<EmailMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        Map<Long, Long> counts = new LinkedHashMap<>();
        long ungrouped = 0;
        for (EmailMessage message : messages) {
            if (message.getProjectId() == null) ungrouped++;
            else counts.merge(message.getProjectId(), 1L, Long::sum);
        }
        Map<Long, Project> projects = loadProjects(counts.keySet());
        List<EmailProjectGroupView> result = new ArrayList<>();
        result.add(new EmailProjectGroupView(null, "未分组", "未分组", ungrouped));
        counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> {
                    Project project = projects.get(entry.getKey());
                    if (project == null) return;
                    result.add(new EmailProjectGroupView(project.getId(), project.getName(),
                            project.getFullPath(), entry.getValue()));
                });
        return result;
    }

    private void execute(Long ownerUserId, boolean regroupAll, LocalDateTime startedAt) {
        int processed = 0;
        int grouped = 0;
        int ungrouped = 0;
        try {
            List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .eq(Project::getStatus, 1)
                    .orderByAsc(Project::getLevel)
                    .orderByAsc(Project::getFullPath));
            Set<Long> validProjectIds = projects.stream().map(Project::getId)
                    .collect(java.util.stream.Collectors.toSet());
            List<EmailMessage> messages = messageMapper.selectList(eligibleQuery(ownerUserId, regroupAll)
                    .orderByAsc(EmailMessage::getId));
            if (projects.isEmpty()) {
                for (EmailMessage message : messages) {
                    saveUngrouped(message, "系统中暂无可用项目");
                    processed++;
                    ungrouped++;
                }
                jobs.put(ownerUserId, new EmailGroupingJobStatus(
                        "SUCCESS", messages.size(), processed, grouped, ungrouped,
                        "系统中暂无可用项目，邮件已归入未分组", startedAt, LocalDateTime.now()));
                return;
            }
            Map<Long, List<String>> projectTerms = projects.stream().collect(
                    java.util.stream.Collectors.toMap(Project::getId, this::terms));
            for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
                List<EmailMessage> batch = messages.subList(from, Math.min(messages.size(), from + BATCH_SIZE));
                Map<Long, EmailProjectAssignment> assignments = new HashMap<>();
                for (EmailMessage message : batch) {
                    deterministicAssignment(message, projectTerms).ifPresent(
                            assignment -> assignments.put(message.getId(), assignment));
                }
                try {
                    deepSeekClient.groupByProjects(batch, projects, ownerUserId)
                            .forEach(item -> assignments.putIfAbsent(item.messageId(), item));
                } catch (RuntimeException exception) {
                    for (EmailMessage message : batch) {
                        saveUngrouped(message, "智能分组失败：" + sanitize(exception.getMessage()));
                        processed++;
                        ungrouped++;
                    }
                    updateRunning(ownerUserId, messages.size(), processed, grouped, ungrouped, startedAt);
                    continue;
                }
                for (EmailMessage message : batch) {
                    EmailProjectAssignment assignment = assignments.get(message.getId());
                    if (assignment != null && assignment.projectId() != null
                            && validProjectIds.contains(assignment.projectId())
                            && assignment.confidence() >= CONFIDENCE_THRESHOLD) {
                        message.setProjectId(assignment.projectId());
                        message.setGroupingStatus("GROUPED");
                        message.setGroupingConfidence(BigDecimal.valueOf(assignment.confidence()));
                        message.setGroupingReason(sanitize(assignment.reason()));
                        grouped++;
                    } else {
                        message.setProjectId(null);
                        message.setGroupingStatus("UNGROUPED");
                        message.setGroupingConfidence(assignment == null ? null
                                : BigDecimal.valueOf(assignment.confidence()));
                        message.setGroupingReason(assignment == null
                                ? "AI 未返回有效分组" : sanitize(assignment.reason()));
                        ungrouped++;
                    }
                    message.setGroupingMethod("AI");
                    message.setGroupingModel(deepSeekClient.configuredModel());
                    message.setGroupedAt(LocalDateTime.now());
                    messageMapper.updateById(message);
                    processed++;
                }
                updateRunning(ownerUserId, messages.size(), processed, grouped, ungrouped, startedAt);
            }
            jobs.put(ownerUserId, new EmailGroupingJobStatus(
                    "SUCCESS", messages.size(), processed, grouped, ungrouped,
                    "智能分组完成", startedAt, LocalDateTime.now()));
        } catch (RuntimeException exception) {
            jobs.put(ownerUserId, new EmailGroupingJobStatus(
                    "FAILED", processed, processed, grouped, ungrouped,
                    sanitize(exception.getMessage()), startedAt, LocalDateTime.now()));
        }
    }

    private Optional<EmailProjectAssignment> deterministicAssignment(
            EmailMessage message, Map<Long, List<String>> projectTerms) {
        String text = ((message.getSubject() == null ? "" : message.getSubject()) + " "
                + (message.getBodyText() == null ? "" : message.getBodyText())).toLowerCase(Locale.ROOT);
        List<Long> matches = projectTerms.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(term -> text.contains(term)))
                .map(Map.Entry::getKey).distinct().toList();
        if (matches.size() != 1) return Optional.empty();
        return Optional.of(new EmailProjectAssignment(message.getId(), matches.get(0), 0.99,
                "邮件标题或正文明确提及项目名称/编码"));
    }

    private List<String> terms(Project project) {
        return java.util.stream.Stream.of(project.getName(), project.getFullPath(), project.getCode())
                .filter(value -> value != null && value.trim().length() >= 2)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct().toList();
    }

    private LambdaQueryWrapper<EmailMessage> eligibleQuery(Long ownerUserId, boolean regroupAll) {
        LambdaQueryWrapper<EmailMessage> query = new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId);
        if (!regroupAll) {
            query.and(wrapper -> wrapper.isNull(EmailMessage::getGroupingStatus)
                    .or().in(EmailMessage::getGroupingStatus, "NOT_GROUPED", "FAILED"));
        }
        return query;
    }

    private void saveUngrouped(EmailMessage message, String reason) {
        message.setProjectId(null);
        message.setGroupingStatus("UNGROUPED");
        message.setGroupingMethod("AI");
        message.setGroupingReason(reason);
        message.setGroupingModel(deepSeekClient.configuredModel());
        message.setGroupedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private void updateRunning(Long userId, int total, int processed, int grouped,
                               int ungrouped, LocalDateTime startedAt) {
        jobs.put(userId, new EmailGroupingJobStatus("RUNNING", total, processed, grouped,
                ungrouped, "正在智能分组", startedAt, null));
    }

    private Map<Long, Project> loadProjects(Set<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return projectMapper.selectBatchIds(new HashSet<>(ids)).stream()
                .collect(java.util.stream.Collectors.toMap(Project::getId, project -> project));
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "无法判断所属项目";
        return value.substring(0, Math.min(500, value.length()));
    }
}
