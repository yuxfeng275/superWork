package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailAccount;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailSenderProjectRule;
import com.bu.management.entity.Project;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailAccountMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailSenderProjectRuleMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.vo.EmailGroupingJobStatus;
import com.bu.management.vo.EmailProjectGroupView;
import com.bu.management.vo.EmailSenderCompanyGroupView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EmailProjectGroupingService {
    private static final int BATCH_SIZE = 15;
    private static final double CONFIDENCE_THRESHOLD = 0.65;
    /** 自动学习阈值：同一发件人对同一项目稳定命中的次数 */
    private static final int LEARN_THRESHOLD = 3;
    /** 地址串兜底正则：从 "张三 <zhangsan+xm@corp.com>" 中抓出纯地址 */
    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("[^\\s@<>,;\"]+@[^\\s@<>,;\"]+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EmailMessageMapper messageMapper;
    private final ProjectMapper projectMapper;
    private final EmailSenderProjectRuleMapper ruleMapper;
    private final DeepSeekDigestClient deepSeekClient;
    private final Executor taskExecutor;
    /** 定时重跑需要遍历启用账号，测试构造时可为 null */
    private final EmailAccountMapper accountMapper;
    private final Map<Long, EmailGroupingJobStatus> jobs = new ConcurrentHashMap<>();

    public EmailProjectGroupingService(
            EmailMessageMapper messageMapper,
            ProjectMapper projectMapper,
            EmailSenderProjectRuleMapper ruleMapper,
            DeepSeekDigestClient deepSeekClient,
            Executor taskExecutor) {
        this(messageMapper, projectMapper, ruleMapper, deepSeekClient, taskExecutor, null);
    }

    @Autowired
    public EmailProjectGroupingService(
            EmailMessageMapper messageMapper,
            ProjectMapper projectMapper,
            EmailSenderProjectRuleMapper ruleMapper,
            DeepSeekDigestClient deepSeekClient,
            @Qualifier("emailTaskExecutor") Executor taskExecutor,
            EmailAccountMapper accountMapper) {
        this.messageMapper = messageMapper;
        this.projectMapper = projectMapper;
        this.ruleMapper = ruleMapper;
        this.deepSeekClient = deepSeekClient;
        this.taskExecutor = taskExecutor;
        this.accountMapper = accountMapper;
    }

    /** 每日自动重跑：全量重分组，兜住别名、规则与项目归属的变更 */
    @Scheduled(cron = "${email.regroup-cron:0 30 2 * * *}", zone = "Asia/Shanghai")
    public void nightlyRegroup() {
        if (accountMapper == null) return;
        List<Long> owners = accountMapper.selectEnabledAccounts().stream()
                .map(EmailAccount::getOwnerUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 已有 RUNNING 任务时 startAsync 直接返回当前任务，此处不阻塞
        owners.forEach(ownerUserId -> taskExecutor.execute(() -> startAsync(ownerUserId, true)));
        log.info("每日邮件重分组：已提交 {} 个邮箱账号", owners.size());
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

    public List<EmailSenderCompanyGroupView> senderCompanies(Long ownerUserId) {
        List<EmailMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EmailMessage message : messages) {
            String domain = senderDomain(message.getSenderAddress());
            counts.merge(domain, 1L, Long::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> new EmailSenderCompanyGroupView(
                        entry.getKey(), companyName(entry.getKey()), entry.getValue()))
                .toList();
    }

    private String senderDomain(String address) {
        if (address == null || !address.contains("@")) return "unknown";
        String domain = address.substring(address.lastIndexOf('@') + 1)
                .trim().toLowerCase(Locale.ROOT);
        return domain.isBlank() ? "unknown" : domain;
    }

    private String companyName(String domain) {
        if ("unknown".equals(domain)) return "未知发件人";
        String name = domain.startsWith("mail.") ? domain.substring(5) : domain;
        return name;
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
            // 项目邮箱别名即 project.code：投递地址 = 账号 +code@域名
            Map<String, Long> aliasProjects = new HashMap<>();
            projects.stream()
                    .filter(project -> project.getCode() != null && !project.getCode().isBlank())
                    .forEach(project -> aliasProjects.putIfAbsent(
                            normalizeAlias(project.getCode()), project.getId()));
            // 发件人路由规则：人工纠偏沉淀，优先于关键词与 AI
            Map<String, Long> senderRules = new HashMap<>();
            ruleMapper.selectList(new LambdaQueryWrapper<EmailSenderProjectRule>()
                    .eq(EmailSenderProjectRule::getOwnerUserId, ownerUserId))
                    .forEach(rule -> senderRules.put(rule.getSenderPattern(), rule.getProjectId()));
            for (int from = 0; from < messages.size(); from += BATCH_SIZE) {
                List<EmailMessage> batch = messages.subList(from, Math.min(messages.size(), from + BATCH_SIZE));
                Map<Long, EmailProjectAssignment> assignments = new HashMap<>();
                for (EmailMessage message : batch) {
                    aliasAssignment(message, aliasProjects)
                            .or(() -> ruleAssignment(message, senderRules))
                            .or(() -> deterministicAssignment(message, projectTerms))
                            .ifPresent(assignment -> assignments.put(message.getId(), assignment));
                }
                try {
                    deepSeekClient.groupByProjects(batch, projects, ownerUserId)
                            .forEach(item -> assignments.putIfAbsent(item.messageId(), item));
                } catch (RuntimeException exception) {
                    // AI 不可用时保留别名 / 发件人规则 / 关键词已确定的归属，只降级其余邮件
                    int preserved = 0;
                    for (EmailMessage message : batch) {
                        EmailProjectAssignment assignment = assignments.get(message.getId());
                        if (assignment != null && assignment.projectId() != null
                                && validProjectIds.contains(assignment.projectId())
                                && assignment.confidence() >= CONFIDENCE_THRESHOLD) {
                            applyGrouped(message, assignment);
                            grouped++;
                            preserved++;
                        } else {
                            applyUngrouped(message, assignment,
                                    "智能分组失败：" + sanitize(exception.getMessage()));
                            ungrouped++;
                        }
                        processed++;
                    }
                    log.info("AI 分组不可用，已保留确定性归属 {} 封、降级未分组 {} 封",
                            preserved, batch.size() - preserved);
                    updateRunning(ownerUserId, messages.size(), processed, grouped, ungrouped, startedAt);
                    continue;
                }
                for (EmailMessage message : batch) {
                    EmailProjectAssignment assignment = assignments.get(message.getId());
                    if (assignment != null && assignment.projectId() != null
                            && validProjectIds.contains(assignment.projectId())
                            && assignment.confidence() >= CONFIDENCE_THRESHOLD) {
                        applyGrouped(message, assignment);
                        grouped++;
                    } else {
                        applyUngrouped(message, assignment, "AI 未返回有效分组");
                        ungrouped++;
                    }
                    processed++;
                }
                updateRunning(ownerUserId, messages.size(), processed, grouped, ungrouped, startedAt);
            }
            int learned = learnSenderRules(ownerUserId, senderRules);
            log.info("邮件智能分组完成：owner {} 处理 {} 封，已分组 {} 封，未分组 {} 封，自动学习新增规则 {} 条",
                    ownerUserId, processed, grouped, ungrouped, learned);
            jobs.put(ownerUserId, new EmailGroupingJobStatus(
                    "SUCCESS", messages.size(), processed, grouped, ungrouped,
                    learned > 0 ? "智能分组完成，新增自动规则 " + learned + " 条" : "智能分组完成",
                    startedAt, LocalDateTime.now()));
        } catch (RuntimeException exception) {
            jobs.put(ownerUserId, new EmailGroupingJobStatus(
                    "FAILED", processed, processed, grouped, ungrouped,
                    sanitize(exception.getMessage()), startedAt, LocalDateTime.now()));
        }
    }

    /**
     * 项目邮箱别名路由（最高优先级）：
     * 解析收件人/抄送地址 local part 中 '+' 后的 token，与 project.code 比对（忽略大小写与空白）。
     */
    private Optional<EmailProjectAssignment> aliasAssignment(
            EmailMessage message, Map<String, Long> aliasProjects) {
        if (aliasProjects.isEmpty()) return Optional.empty();
        List<String> addresses = new ArrayList<>();
        addresses.addAll(parseAddresses(message.getToAddressesJson()));
        addresses.addAll(parseAddresses(message.getCcAddressesJson()));
        for (String address : addresses) {
            String token = aliasToken(address);
            if (token == null) continue;
            Long projectId = aliasProjects.get(normalizeAlias(token));
            if (projectId == null) continue;
            String alias = "+" + token.toUpperCase(Locale.ROOT);
            message.setGroupingAlias(alias);
            return Optional.of(new EmailProjectAssignment(message.getId(), projectId, 1.0,
                    "命中项目邮箱别名 " + alias));
        }
        return Optional.empty();
    }

    /** 解析地址 JSON：优先 Jackson 反序列化 List&lt;String&gt;，失败则正则兜底 */
    private List<String> parseAddresses(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            List<String> addresses = new ArrayList<>();
            for (String value : values) {
                String address = extractAddress(value);
                if (address != null) addresses.add(address);
            }
            return addresses;
        } catch (Exception ignored) {
            Matcher matcher = ADDRESS_PATTERN.matcher(json);
            List<String> addresses = new ArrayList<>();
            while (matcher.find()) addresses.add(matcher.group());
            return addresses;
        }
    }

    private String extractAddress(String value) {
        if (value == null) return null;
        Matcher matcher = ADDRESS_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    /** 取 local part 中 '+' 之后的 token，没有别名则返回 null */
    private String aliasToken(String address) {
        if (address == null) return null;
        int at = address.lastIndexOf('@');
        if (at <= 0) return null;
        String local = address.substring(0, at);
        int plus = local.indexOf('+');
        if (plus < 0 || plus == local.length() - 1) return null;
        String token = local.substring(plus + 1).trim();
        return token.isEmpty() ? null : token;
    }

    /** 别名比对口径：忽略大小写与空白 */
    private String normalizeAlias(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 发件人路由规则：先精确地址后域名，命中即分组（置信度 1.0） */
    private Optional<EmailProjectAssignment> ruleAssignment(
            EmailMessage message, Map<String, Long> senderRules) {
        if (senderRules.isEmpty()) return Optional.empty();
        String sender = message.getSenderAddress() == null ? ""
                : message.getSenderAddress().trim().toLowerCase(Locale.ROOT);
        if (sender.isEmpty()) return Optional.empty();
        Long projectId = senderRules.get(sender);
        if (projectId == null) projectId = senderRules.get(senderDomain(sender));
        if (projectId == null) return Optional.empty();
        return Optional.of(new EmailProjectAssignment(message.getId(), projectId, 1.0,
                "命中发件人路由规则"));
    }

    /** 人工指定邮件归属项目，并沉淀为发件人路由规则 */
    public EmailMessage assignManually(Long ownerUserId, Long messageId, Long projectId) {
        EmailMessage message = messageMapper.selectOne(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getId, messageId)
                .eq(EmailMessage::getOwnerUserId, ownerUserId));
        if (message == null) throw new IllegalArgumentException("邮件不存在");
        if (projectId == null || projectMapper.selectById(projectId) == null) {
            throw new IllegalArgumentException("项目不存在");
        }
        message.setProjectId(projectId);
        message.setGroupingStatus("GROUPED");
        message.setGroupingMethod("MANUAL");
        message.setGroupingConfidence(null);
        message.setGroupingReason("人工指定");
        message.setGroupedAt(LocalDateTime.now());
        messageMapper.updateById(message);

        String sender = message.getSenderAddress() == null ? ""
                : message.getSenderAddress().trim().toLowerCase(Locale.ROOT);
        if (!sender.isEmpty()) {
            EmailSenderProjectRule rule = ruleMapper.selectOne(
                    new LambdaQueryWrapper<EmailSenderProjectRule>()
                            .eq(EmailSenderProjectRule::getOwnerUserId, ownerUserId)
                            .eq(EmailSenderProjectRule::getSenderPattern, sender));
            if (rule == null) {
                rule = new EmailSenderProjectRule();
                rule.setOwnerUserId(ownerUserId);
                rule.setSenderPattern(sender);
                rule.setProjectId(projectId);
                rule.setSource("MANUAL");
                rule.setHitCount(1);
                ruleMapper.insert(rule);
            } else {
                rule.setProjectId(projectId);
                rule.setHitCount((rule.getHitCount() == null ? 0 : rule.getHitCount()) + 1);
                ruleMapper.updateById(rule);
            }
        }
        return message;
    }

    /**
     * 归属自动学习：同一发件人对同一项目稳定命中 ≥3 次、且当前无任何规则时，
     * 沉淀为 source='AUTO' 的路由规则；历史命中过多个项目视为冲突，不写。
     */
    private int learnSenderRules(Long ownerUserId, Map<String, Long> existingRules) {
        List<EmailMessage> groupedMessages = messageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getOwnerUserId, ownerUserId)
                .eq(EmailMessage::getGroupingStatus, "GROUPED")
                .isNotNull(EmailMessage::getProjectId));
        // 发件人 -> (项目 -> 命中次数)
        Map<String, Map<Long, Integer>> tally = new LinkedHashMap<>();
        for (EmailMessage message : groupedMessages) {
            String method = message.getGroupingMethod();
            if (method != null && method.toUpperCase(Locale.ROOT).contains("MANUAL")) continue;
            String sender = message.getSenderAddress() == null ? ""
                    : message.getSenderAddress().trim().toLowerCase(Locale.ROOT);
            if (sender.isEmpty()) continue;
            tally.computeIfAbsent(sender, key -> new LinkedHashMap<>())
                    .merge(message.getProjectId(), 1, Integer::sum);
        }
        int learned = 0;
        for (Map.Entry<String, Map<Long, Integer>> entry : tally.entrySet()) {
            String sender = entry.getKey();
            if (existingRules.containsKey(sender)) continue;
            Map<Long, Integer> perProject = entry.getValue();
            if (perProject.size() > 1) continue;
            Map.Entry<Long, Integer> only = perProject.entrySet().iterator().next();
            if (only.getValue() < LEARN_THRESHOLD) continue;
            EmailSenderProjectRule rule = new EmailSenderProjectRule();
            rule.setOwnerUserId(ownerUserId);
            rule.setSenderPattern(sender);
            rule.setProjectId(only.getKey());
            rule.setSource("AUTO");
            rule.setHitCount(only.getValue());
            ruleMapper.insert(rule);
            existingRules.put(sender, only.getKey());
            learned++;
        }
        if (learned > 0) {
            log.info("邮件归属自动学习：owner {} 新增发件人规则 {} 条", ownerUserId, learned);
        }
        return learned;
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
        Set<String> generic = Set.of("ai", "crm", "项目", "产品", "数据", "系统", "平台", "研发", "业务");
        return java.util.stream.Stream.of(project.getName(), project.getFullPath(), project.getCode())
                .filter(value -> value != null && value.trim().length() >= 3)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !generic.contains(value))
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

    private void applyGrouped(EmailMessage message, EmailProjectAssignment assignment) {
        message.setProjectId(assignment.projectId());
        message.setGroupingStatus("GROUPED");
        message.setGroupingConfidence(BigDecimal.valueOf(assignment.confidence()));
        message.setGroupingReason(sanitize(assignment.reason()));
        message.setGroupingMethod("AI");
        message.setGroupingModel(deepSeekClient.configuredModel());
        message.setGroupedAt(LocalDateTime.now());
        messageMapper.updateById(message);
    }

    private void applyUngrouped(EmailMessage message, EmailProjectAssignment assignment, String reason) {
        message.setProjectId(null);
        message.setGroupingStatus("UNGROUPED");
        message.setGroupingConfidence(assignment == null ? null
                : BigDecimal.valueOf(assignment.confidence()));
        message.setGroupingReason(assignment == null ? reason : sanitize(assignment.reason()));
        message.setGroupingMethod("AI");
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
