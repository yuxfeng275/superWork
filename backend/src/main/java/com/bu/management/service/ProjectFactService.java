package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.YunxiaoWorkitemCache;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 项目事实层：把「已经进系统」的信息按项目归一成一条条可溯源的事实。
 *
 * <p>约束（见 docs/project-agent-loop-plan.md §5.1）：
 * <ul>
 *   <li>只抄不改：每条事实都带 source_type + source_ref，能点回原始会议/邮件/工作项；</li>
 *   <li>幂等：dedupe_hash = sha256(source_type|source_ref)，重复汇入只更新不新增；</li>
 *   <li>会话语义：会议沿用「上传人可见」的既有口径，其余按项目可见；</li>
 *   <li>体量受控：单源每次最多拉 maxSourceRows 行，首次汇入不会被历史数据拖垮。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFactService {

    public static final String SOURCE_MEETING = "MEETING";
    public static final String SOURCE_EMAIL = "EMAIL";
    public static final String SOURCE_WORKITEM = "WORKITEM";
    public static final String SOURCE_KEY_MATTER = "KEY_MATTER";

    /** 手工投递来源：本机文件上传、补充说明、聊天记录导出（见 ProjectFactIntakeController）。 */
    public static final String SOURCE_FILE_UPLOAD = "FILE_UPLOAD";
    public static final String SOURCE_NOTE = "NOTE";
    public static final String SOURCE_CHAT_EXPORT = "CHAT_EXPORT";

    /** 制品采纳后的回流来源（不参与系统内汇入，只由 AiArtifactService 写入）。 */
    public static final String SOURCE_ARTIFACT = "ARTIFACT";

    public static final String VISIBILITY_PROJECT = "PROJECT";
    public static final String VISIBILITY_COMPANY = "PRIVATE_COMPANY";
    public static final String VISIBILITY_OWNER = "OWNER";

    private static final int MAX_TITLE = 300;
    private static final int MAX_DIGEST = 1000;
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final ProjectFactMapper factMapper;
    private final ProjectMapper projectMapper;
    private final MeetingMapper meetingMapper;
    private final EmailMessageMapper emailMessageMapper;
    private final YunxiaoWorkitemCacheMapper workitemCacheMapper;
    private final BuKeyMatterMapper keyMatterMapper;
    private final ProjectContextProperties properties;

    /** 单个项目的一次汇入；返回新增或更新的条数。 */
    @Transactional
    public int ingestProject(Long projectId, LocalDateTime since) {
        if (projectId == null) {
            return 0;
        }
        int changed = 0;
        changed += ingestMeetings(projectId, since);
        changed += ingestEmails(projectId, since);
        changed += ingestWorkitems(projectId, since);
        changed += ingestKeyMatters(projectId, since);
        return changed;
    }

    /** 全部项目的一次汇入（定时任务用）；返回新增或更新的条数。 */
    public int ingestAllSince(LocalDateTime since) {
        List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .select(Project::getId));
        int changed = 0;
        for (Project project : projects) {
            try {
                changed += ingestProject(project.getId(), since);
            } catch (Exception e) {
                // 单个项目汇入失败不影响其它项目
                log.warn("项目 {} 事实汇入失败: {}", project.getId(), e.getMessage());
            }
        }
        return changed;
    }

    /** 供 autoloop 读取的项目上下文；types 为空表示全部来源。 */
    public List<ProjectFact> query(Long projectId, List<String> types, LocalDateTime since, int limit) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        LambdaQueryWrapper<ProjectFact> wrapper = new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getProjectId, projectId)
                .orderByDesc(ProjectFact::getHappenedAt)
                .last("limit " + size);
        if (types != null && !types.isEmpty()) {
            wrapper.in(ProjectFact::getSourceType, types);
        }
        if (since != null) {
            wrapper.ge(ProjectFact::getHappenedAt, since);
        }
        return factMapper.selectList(wrapper);
    }

    /**
     * 供 AI 工具读取：只返回当前用户有权看到的事实。
     * PROJECT / PRIVATE_COMPANY 项目内可见；OWNER 仅本人（会议 v1 口径）。
     */
    public List<ProjectFact> queryForAgent(Long userId, Long projectId, List<String> types,
            LocalDateTime since, int limit) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        LambdaQueryWrapper<ProjectFact> wrapper = new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getProjectId, projectId)
                .and(scope -> scope
                        .in(ProjectFact::getVisibility, List.of(VISIBILITY_PROJECT, VISIBILITY_COMPANY))
                        .or(owner -> owner.eq(ProjectFact::getVisibility, VISIBILITY_OWNER)
                                .eq(ProjectFact::getActorId, userId)))
                .orderByDesc(ProjectFact::getHappenedAt)
                .last("limit " + size);
        if (types != null && !types.isEmpty()) {
            wrapper.in(ProjectFact::getSourceType, types);
        }
        if (since != null) {
            wrapper.ge(ProjectFact::getHappenedAt, since);
        }
        return factMapper.selectList(wrapper);
    }

    /** 一次汇入涉及的项目（便于人工确认「皇家项目」绑对了）。 */
    public Set<Long> projectsWithFacts() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ProjectFact fact : factMapper.selectList(new LambdaQueryWrapper<ProjectFact>()
                .select(ProjectFact::getProjectId).last("limit " + MAX_LIMIT))) {
            if (fact.getProjectId() != null) {
                ids.add(fact.getProjectId());
            }
        }
        return ids;
    }

    // ==================== 来源适配器 ====================

    private int ingestMeetings(Long projectId, LocalDateTime since) {
        List<Meeting> rows = meetingMapper.selectList(new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getProjectId, projectId)
                .ge(Meeting::getUpdatedAt, since)
                .orderByDesc(Meeting::getUpdatedAt)
                .last("limit " + properties.getMaxSourceRows()));
        int changed = 0;
        for (Meeting meeting : rows) {
            LocalDateTime happenedAt = meeting.getMeetingDate() == null
                    ? meeting.getCreatedAt()
                    : meeting.getMeetingDate().atStartOfDay();
            changed += upsert(new FactDraft(
                    projectId,
                    SOURCE_MEETING,
                    "meeting:" + meeting.getId(),
                    meeting.getOwnerUserId(),
                    happenedAt,
                    truncate(meeting.getTitle(), MAX_TITLE),
                    meeting.getSummaryJson(),
                    // 会议 v1 口径：只有上传人可见
                    VISIBILITY_OWNER));
        }
        return changed;
    }

    private int ingestEmails(Long projectId, LocalDateTime since) {
        List<EmailMessage> rows = emailMessageMapper.selectList(new LambdaQueryWrapper<EmailMessage>()
                .eq(EmailMessage::getProjectId, projectId)
                .ge(EmailMessage::getReceivedAt, since)
                .orderByDesc(EmailMessage::getReceivedAt)
                .last("limit " + properties.getMaxSourceRows()));
        int changed = 0;
        for (EmailMessage mail : rows) {
            LocalDateTime happenedAt = mail.getReceivedAt() == null ? mail.getCreatedAt() : mail.getReceivedAt();
            changed += upsert(new FactDraft(
                    projectId,
                    SOURCE_EMAIL,
                    "email_message:" + mail.getId(),
                    mail.getOwnerUserId(),
                    happenedAt,
                    truncate(mail.getSubject(), MAX_TITLE),
                    mail.getBodyText(),
                    VISIBILITY_PROJECT));
        }
        return changed;
    }

    private int ingestWorkitems(Long projectId, LocalDateTime since) {
        List<YunxiaoWorkitemCache> rows = workitemCacheMapper.selectList(
                new LambdaQueryWrapper<YunxiaoWorkitemCache>()
                        .eq(YunxiaoWorkitemCache::getProjectId, projectId)
                        .eq(YunxiaoWorkitemCache::getActive, 1)
                        .ge(YunxiaoWorkitemCache::getLastSyncedAt, since)
                        .orderByDesc(YunxiaoWorkitemCache::getLastSyncedAt)
                        .last("limit " + properties.getMaxSourceRows()));
        int changed = 0;
        for (YunxiaoWorkitemCache item : rows) {
            LocalDateTime happenedAt = item.getLastSyncedAt() == null
                    ? item.getCreatedAt()
                    : item.getLastSyncedAt();
            changed += upsert(new FactDraft(
                    projectId,
                    SOURCE_WORKITEM,
                    "yunxiao_workitem:" + item.getId(),
                    null,
                    happenedAt,
                    truncate(joinSerial(item.getSerialNumber(), item.getTitle()), MAX_TITLE),
                    item.getRawJson(),
                    VISIBILITY_PROJECT));
        }
        return changed;
    }

    private int ingestKeyMatters(Long projectId, LocalDateTime since) {
        List<BuKeyMatter> rows = keyMatterMapper.selectList(new LambdaQueryWrapper<BuKeyMatter>()
                .eq(BuKeyMatter::getProjectId, projectId)
                .ge(BuKeyMatter::getUpdatedAt, since)
                .orderByDesc(BuKeyMatter::getUpdatedAt)
                .last("limit " + properties.getMaxSourceRows()));
        int changed = 0;
        for (BuKeyMatter matter : rows) {
            LocalDateTime happenedAt = matter.getUpdatedAt() == null
                    ? matter.getCreatedAt()
                    : matter.getUpdatedAt();
            changed += upsert(new FactDraft(
                    projectId,
                    SOURCE_KEY_MATTER,
                    "bu_key_matter:" + matter.getId(),
                    matter.getOwnerId(),
                    happenedAt,
                    truncate(matter.getTitle(), MAX_TITLE),
                    matter.getDescription(),
                    VISIBILITY_PROJECT));
        }
        return changed;
    }

    // ==================== 落库 ====================

    /**
     * 采纳回流：制品被应用（APPROVED → APPLIED）后写成一条项目事实，采纳即新事实。
     *
     * <p>source_type=ARTIFACT、source_ref=ai_artifact:{id}，按 dedupe_hash 幂等，
     * 重复采纳同一制品只更新不重复；项目可见，不归属到某个具体人。
     */
    @Transactional
    public void recordArtifactFact(Long projectId, Long artifactId, String title, String content) {
        if (projectId == null || artifactId == null) {
            throw new IllegalArgumentException("项目 ID 与制品 ID 不能为空");
        }
        upsert(new FactDraft(
                projectId,
                SOURCE_ARTIFACT,
                "ai_artifact:" + artifactId,
                null,
                LocalDateTime.now(),
                truncate(title, MAX_TITLE),
                content,
                VISIBILITY_PROJECT));
    }

    /**
     * 手工投递入口（文件 / 补充说明 / 聊天记录）：直接写一条事实，按 dedupe_hash 幂等。
     *
     * @param body 可为 null；title 截断到 300 字；visibility 缺省为项目可见
     * @return 新增或更新的条数（0 表示入参不完整被跳过）
     */
    @Transactional
    public int appendManual(Long projectId, String sourceType, String sourceRef, Long actorId,
            LocalDateTime happenedAt, String title, String body, String visibility) {
        if (projectId == null || !StringUtils.hasText(sourceType) || !StringUtils.hasText(sourceRef)) {
            return 0;
        }
        return upsert(new FactDraft(
                projectId,
                sourceType.strip(),
                sourceRef.strip(),
                actorId,
                happenedAt == null ? LocalDateTime.now() : happenedAt,
                truncate(title, MAX_TITLE),
                body,
                StringUtils.hasText(visibility) ? visibility.strip() : VISIBILITY_PROJECT));
    }

    /** 按来源回读一条事实（投递接口用来把落库结果返回给调用方）。 */
    public ProjectFact find(String sourceType, String sourceRef) {
        if (!StringUtils.hasText(sourceType) || !StringUtils.hasText(sourceRef)) {
            return null;
        }
        return factMapper.selectOne(new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getDedupeHash, dedupeHash(sourceType.strip(), sourceRef.strip()))
                .last("limit 1"));
    }

    private record FactDraft(Long projectId, String sourceType, String sourceRef, Long actorId,
                             LocalDateTime happenedAt, String title, String body, String visibility) {
    }

    private int upsert(FactDraft draft) {
        if (!StringUtils.hasText(draft.sourceRef()) || draft.happenedAt() == null) {
            return 0;
        }
        String hash = dedupeHash(draft.sourceType(), draft.sourceRef());
        String digest = digestOf(draft.title(), draft.body());
        LocalDateTime now = LocalDateTime.now();
        ProjectFact existing = factMapper.selectOne(new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getDedupeHash, hash)
                .last("limit 1"));
        if (existing == null) {
            ProjectFact fact = new ProjectFact();
            fact.setProjectId(draft.projectId());
            fact.setSourceType(draft.sourceType());
            fact.setSourceRef(draft.sourceRef());
            fact.setActorId(draft.actorId());
            fact.setHappenedAt(draft.happenedAt());
            fact.setTitle(draft.title());
            fact.setBody(draft.body());
            fact.setDigest(digest);
            fact.setVisibility(draft.visibility());
            fact.setDedupeHash(hash);
            fact.setCreatedAt(now);
            fact.setUpdatedAt(now);
            return factMapper.insert(fact) > 0 ? 1 : 0;
        }
        existing.setProjectId(draft.projectId());
        existing.setActorId(draft.actorId());
        existing.setHappenedAt(draft.happenedAt());
        existing.setTitle(draft.title());
        existing.setBody(draft.body());
        existing.setDigest(digest);
        existing.setVisibility(draft.visibility());
        existing.setUpdatedAt(now);
        return factMapper.updateById(existing) > 0 ? 1 : 0;
    }

    /** 摘要：标题 + 正文前若干字，供模型消费；正文不进提示词。 */
    static String digestOf(String title, String body) {
        String head = StringUtils.hasText(title) ? title.strip() : "";
        String tail = StringUtils.hasText(body) ? body.strip() : "";
        if (tail.isEmpty()) {
            return truncate(head, MAX_DIGEST);
        }
        int room = MAX_DIGEST - head.length() - 3;
        return room <= 0 ? head : head + (head.isEmpty() ? "" : " · ") + tail.substring(0, Math.min(room, tail.length()));
    }

    /** 幂等键：同一源对象（会议/邮件/工作项）反复汇入只更新不新增。 */
    public static String dedupeHash(String sourceType, String sourceRef) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] raw = (sourceType + "|" + sourceRef).getBytes(StandardCharsets.UTF_8);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha256.digest(raw)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String joinSerial(String serial, String title) {
        List<String> parts = new ArrayList<>(2);
        if (StringUtils.hasText(serial)) {
            parts.add(serial.strip());
        }
        if (StringUtils.hasText(title)) {
            parts.add(title.strip());
        }
        return String.join(" ", parts);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }
}
