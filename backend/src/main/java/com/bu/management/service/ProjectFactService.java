package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    /** 低质阈值：body 空且 digest 短于该长度就算低质（只有标题的空壳）。 */
    private static final int LOW_QUALITY_DIGEST_MIN = 40;

    private final ProjectFactMapper factMapper;
    private final ProjectMapper projectMapper;
    private final MeetingMapper meetingMapper;
    private final EmailMessageMapper emailMessageMapper;
    private final YunxiaoWorkitemCacheMapper workitemCacheMapper;
    private final BuKeyMatterMapper keyMatterMapper;
    private final ProjectContextProperties properties;
    private final ProjectFactPolicy factPolicy;
    /** 入库前脉络：五类敏感数据在这里就被换成占位符，原文绝不落库。 */
    private final FactRedactionService redactionService;
    /** 会议自动归属：上传时没认领项目的会议先在这里找唯一确定的项目，只查不写。 */
    private final MeetingProjectAttributionService meetingAttributionService;

    /** 公司域名白名单（逗号分隔），空则来信一律按项目可见（不猜）。 */
    @Value("${email.company-domains:}")
    private String companyDomains;

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
        return query(projectId, types, since, limit, 0);
    }

    /**
     * 分页取事实：排序口径与上面完全一致（happened_at 倒序），额外跳过前 offset 条。
     *
     * <p>汇总要算全量数字，必须靠它一页页翻。排序再加 id 倒序兜底：只有 happened_at 一个排序键时，
     * 同一时刻的多条事实在翻页之间顺序不稳定，limit/offset 会重复或漏行，分页统计出来的总量就是错的。
     * offset 小于 0 按 0 处理（SQL 里负 offset 直接报错）。
     */
    public List<ProjectFact> query(Long projectId, List<String> types, LocalDateTime since, int limit,
            int offset) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        LambdaQueryWrapper<ProjectFact> wrapper = new LambdaQueryWrapper<ProjectFact>()
                .eq(ProjectFact::getProjectId, projectId)
                .orderByDesc(ProjectFact::getHappenedAt)
                .orderByDesc(ProjectFact::getId)
                .last("limit " + size + " offset " + Math.max(offset, 0));
        if (types != null && !types.isEmpty()) {
            wrapper.in(ProjectFact::getSourceType, types);
        }
        if (since != null) {
            wrapper.ge(ProjectFact::getHappenedAt, since);
        }
        return factMapper.selectList(wrapper);
    }

    /**
     * 供 AI 工具读取：先判项目准入，再按 {@link ProjectFactPolicy} 过滤可见性。
     *
     * <p>这里的 projectId 是模型给的，绝不能信：非成员换个 ID 就能读到别人的项目事实。
     * 准入不通过直接返回空列表（工具路径要好读，不抛异常）；
     * 可见性口径与网页列表同一份实现，不再在 SQL 里另拼一套条件。
     */
    public List<ProjectFact> queryForAgent(Long userId, Long projectId, List<String> types,
            LocalDateTime since, int limit) {
        if (!factPolicy.canRead(userId, projectId)) {
            log.debug("AI 取数拒绝：用户 {} 对项目 {} 无读权限，返回空事实", userId, projectId);
            return List.of();
        }
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
        return factPolicy.visible(userId, projectId, factMapper.selectList(wrapper));
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
        // ① 没认领项目的会议先自动归属：三条规则唯一命中才回写 project_id，猜不准就原地不动。
        List<Meeting> orphans = meetingMapper.selectList(new LambdaQueryWrapper<Meeting>()
                .isNull(Meeting::getProjectId)
                .ge(Meeting::getUpdatedAt, since)
                .orderByDesc(Meeting::getUpdatedAt)
                .last("limit " + properties.getMaxSourceRows()));
        for (Meeting orphan : orphans) {
            if (orphan.getProjectId() != null) {
                continue;
            }
            Long attributed = meetingAttributionService.attribute(orphan);
            if (attributed == null) {
                continue;
            }
            orphan.setProjectId(attributed);
            meetingMapper.updateById(orphan);
        }

        // ② 已归属的会议（含刚回写的）：照旧 upsert 事实，v1 口径仍是只有上传人可见。
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
        // 可见性由发件人域决定：白名单为空一律按项目可见；发件人域不在白名单里的外部来信降级为公司可见。
        Set<String> companyDomainSet = new LinkedHashSet<>();
        if (StringUtils.hasText(companyDomains)) {
            for (String domain : companyDomains.split(",")) {
                String value = domain.trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    companyDomainSet.add(value);
                }
            }
        }
        int changed = 0;
        for (EmailMessage mail : rows) {
            LocalDateTime happenedAt = mail.getReceivedAt() == null ? mail.getCreatedAt() : mail.getReceivedAt();
            String senderDomain = null;
            if (mail.getSenderAddress() != null) {
                int at = mail.getSenderAddress().lastIndexOf('@');
                if (at >= 0 && at < mail.getSenderAddress().length() - 1) {
                    senderDomain = mail.getSenderAddress().substring(at + 1).trim().toLowerCase(Locale.ROOT);
                }
            }
            // 域无法判定时不猜，按项目可见；确认是外部域才降级
            String visibility = !companyDomainSet.isEmpty()
                    && senderDomain != null
                    && !companyDomainSet.contains(senderDomain)
                    ? VISIBILITY_COMPANY
                    : VISIBILITY_PROJECT;
            changed += upsert(new FactDraft(
                    projectId,
                    SOURCE_EMAIL,
                    "email_message:" + mail.getId(),
                    mail.getOwnerUserId(),
                    happenedAt,
                    truncate(mail.getSubject(), MAX_TITLE),
                    mail.getBodyText(),
                    visibility));
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

    /**
     * 统一空间的文件投递：把一份文件变成一条 {@code FILE_UPLOAD} 事实。
     *
     * <p>同一文件重复投递时 source_ref 不变（{@code file:<id>}），dedupe_hash 自然命中同一条事实，
     * 只刷新摘要与时间；归属与可见性沿用原投递人（见 {@code upsert} 的 foreignActor 保护）。
     *
     * @param projectId 归属项目
     * @param actorId   投递人（决定归属与可见性）
     * @param fileId    ai_file.id
     * @param filename  原始文件名
     * @param textForDigest 供摘要使用的文本（文件名 + 大小 + 文本开头），不存原文
     * @param happenedAt 投递时间
     * @param visibility PROJECT / PRIVATE_COMPANY / OWNER
     * @return 新增或刷新 1，跳过 0
     */
    public int upsertFileFact(Long projectId, Long actorId, Long fileId, String filename,
                              String textForDigest, LocalDateTime happenedAt, String visibility) {
        if (projectId == null || fileId == null) {
            return 0;
        }
        return upsert(new FactDraft(projectId, SOURCE_FILE_UPLOAD, "file:" + fileId, actorId,
                happenedAt, filename, textForDigest,
                visibility == null || visibility.isBlank() ? VISIBILITY_PROJECT : visibility));
    }

    private record FactDraft(Long projectId, String sourceType, String sourceRef, Long actorId,
                             LocalDateTime happenedAt, String title, String body, String visibility) {
    }

    private int upsert(FactDraft draft) {
        if (!StringUtils.hasText(draft.sourceRef()) || draft.happenedAt() == null) {
            return 0;
        }
        // 先脉络再落库：title / body 都换成脉络后的文本，digest 也基于脉络后的内容，原文绝不入库
        String safeTitle = redactionService.redact(draft.title());
        String safeBody = redactionService.redact(draft.body());
        String hash = dedupeHash(draft.sourceType(), draft.sourceRef());
        String digest = digestOf(safeTitle, safeBody);
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
            fact.setTitle(safeTitle);
            fact.setBody(safeBody);
            fact.setDigest(digest);
            fact.setVisibility(draft.visibility());
            fact.setDedupeHash(hash);
            fact.setCreatedAt(now);
            fact.setUpdatedAt(now);
            return factMapper.insert(fact) > 0 ? 1 : 0;
        }
        // 同 sha256 的来源被第二个人再投一次时，内容可以刷新，但归属与可见性属于原投递人：
        // 无条件改写会把别人的事实“偷”到自己名下，或把项目可见的证据收窄成仅本人可见。
        boolean foreignActor = existing.getActorId() != null
                && !Objects.equals(existing.getActorId(), draft.actorId());
        existing.setProjectId(draft.projectId());
        if (!foreignActor) {
            existing.setActorId(draft.actorId());
            existing.setHappenedAt(draft.happenedAt());
            existing.setVisibility(draft.visibility());
        }
        existing.setTitle(safeTitle);
        existing.setBody(safeBody);
        existing.setDigest(digest);
        existing.setUpdatedAt(now);
        if (foreignActor) {
            // 只记项目与来源类型，不记正文内容
            log.debug("事实去重命中他人来源，保留原归属与可见性: projectId={}, sourceType={}",
                    draft.projectId(), draft.sourceType());
        }
        return factMapper.updateById(existing) > 0 ? 1 : 0;
    }

    /**
     * 保留期清理：把到期的原文正文置 null，事实行与 title / digest / source_ref 全部保留。
     *
     * <p>只动传入的 source_types（目前只有 FILE_UPLOAD / CHAT_EXPORT），不会被清理归入其它来源。
     * 已经清过的行（body 为 null 或空串）不重复计入。
     *
     * @param before      时间阀值：happened_at 早于它的才清
     * @param sourceTypes 参与清理的来源类型
     * @return 被清掉正文的条数
     */
    @Transactional
    public int purgeBodies(LocalDateTime before, List<String> sourceTypes) {
        if (before == null || sourceTypes == null || sourceTypes.isEmpty()) {
            return 0;
        }
        LambdaUpdateWrapper<ProjectFact> wrapper = new LambdaUpdateWrapper<ProjectFact>()
                .in(ProjectFact::getSourceType, sourceTypes)
                .lt(ProjectFact::getHappenedAt, before)
                .isNotNull(ProjectFact::getBody)
                .ne(ProjectFact::getBody, "")
                .set(ProjectFact::getBody, null)
                .set(ProjectFact::getUpdatedAt, LocalDateTime.now());
        return factMapper.update(null, wrapper);
    }

    /**
     * 事实质量判定：body 为空且 digest 长度小于 40 视为低质（只有一句话、回查不到任何上下文）。
     *
     * <p>阈值取 40 字：一条能用的摘要至少得交待清楚「推 / 時間 / 干系人」，低于这个长度多半是
     * 只有标题的空壳。body 有内容一律算合格（原文还在，回头能查）。
     *
     * @param fact 事实；null 返回 false
     * @return true = 质量合格
     */
    public boolean qualityOf(ProjectFact fact) {
        if (fact == null) {
            return false;
        }
        boolean bodyEmpty = !StringUtils.hasText(fact.getBody());
        int digestLength = fact.getDigest() == null ? 0 : fact.getDigest().strip().length();
        return !(bodyEmpty && digestLength < LOW_QUALITY_DIGEST_MIN);
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
