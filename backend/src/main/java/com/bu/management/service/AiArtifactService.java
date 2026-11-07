package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiArtifact;
import com.bu.management.mapper.AiArtifactMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 制品与评审队列：夜间产出草稿，白天人评审，采纳后回写事实层。
 *
 * <p>约束（见 docs/project-agent-loop-plan.md §5.3）：
 * <ul>
 *   <li>无证据不进制品：evidence 必须是 project_fact.id 的非空数组，否则直接拒绝；</li>
 *   <li>状态单向：DRAFT/REJECTED → IN_REVIEW → APPROVED/REJECTED → APPLIED，非法流转抛 IllegalArgumentException；</li>
 *   <li>驳回必须写原因：作为规则好坏信号，不新造指标体系；</li>
 *   <li>回流：APPLIED 时在同一事务里回写一条 project_fact（source_type=ARTIFACT），采纳即新事实。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiArtifactService {

    public static final String KIND_PLAN = "PLAN";
    public static final String KIND_PATCH = "PATCH";
    public static final String KIND_FIX_NOTE = "FIX_NOTE";
    public static final String KIND_REVIEW_FIX = "REVIEW_FIX";
    public static final String KIND_CHECKLIST = "CHECKLIST";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_IN_REVIEW = "IN_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_APPLIED = "APPLIED";

    /** 允许的制品类型；不在白名单里的一律拒绝，避免模型自创类型污染评审队列。 */
    public static final Set<String> ALLOWED_KINDS = Set.of(
            KIND_PLAN, KIND_PATCH, KIND_FIX_NOTE, KIND_REVIEW_FIX, KIND_CHECKLIST);

    public static final String EVIDENCE_REQUIRED = "制品必须挂证据（project_fact.id）";

    private static final int MAX_TITLE = 300;
    private static final int MAX_REASON = 500;
    private static final String KIND_HINT = "PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST";

    private final AiArtifactMapper artifactMapper;
    private final ProjectFactService projectFactService;
    private final ObjectMapper objectMapper;

    /** 创建请求：projectId 与 taskId 与路径参数同义，二选一传即可。 */
    public record ArtifactCreateRequest(
            String title,
            String kind,
            String content,
            String evidenceJson,
            Long repoId,
            String branch,
            Long projectId,
            String taskId) {
    }

    /** 建草稿：状态恒为 DRAFT，产出者即当前身份。 */
    @Transactional(rollbackFor = Exception.class)
    public AiArtifact create(Long userId, Long projectId, ArtifactCreateRequest req) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Long pid = projectId != null ? projectId : req.projectId();
        if (pid == null || pid <= 0) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        String kind = normalizeKind(req.kind());
        String title = normalizeTitle(req.title());
        if (!StringUtils.hasText(req.content())) {
            throw new IllegalArgumentException("制品正文不能为空");
        }
        String evidence = normalizeEvidence(req.evidenceJson());

        LocalDateTime now = LocalDateTime.now();
        AiArtifact artifact = new AiArtifact();
        artifact.setProjectId(pid);
        artifact.setOwnerUserId(userId);
        artifact.setTaskId(trimToNull(req.taskId(), 64));
        artifact.setKind(kind);
        artifact.setTitle(title);
        artifact.setContent(req.content());
        artifact.setEvidence(evidence);
        artifact.setRepoId(req.repoId());
        artifact.setBranch(trimToNull(req.branch(), 120));
        artifact.setStatus(STATUS_DRAFT);
        artifact.setCreatedAt(now);
        artifact.setUpdatedAt(now);
        artifactMapper.insert(artifact);
        log.info("制品 {} 入库（project={}，kind={}，owner={}）", artifact.getId(), pid, kind, userId);
        return artifact;
    }

    /** 提交评审：只有本人、且处于 DRAFT/REJECTED（驳回后可改后重提）。 */
    @Transactional(rollbackFor = Exception.class)
    public AiArtifact submit(Long userId, Long id) {
        AiArtifact artifact = requireArtifact(id);
        if (!Objects.equals(artifact.getOwnerUserId(), userId)) {
            throw new IllegalArgumentException("只有产出人可以提交评审");
        }
        if (!STATUS_DRAFT.equals(artifact.getStatus()) && !STATUS_REJECTED.equals(artifact.getStatus())) {
            throw new IllegalArgumentException("当前状态（" + artifact.getStatus() + "）不允许提交评审");
        }
        artifact.setStatus(STATUS_IN_REVIEW);
        artifact.setRejectReason(null);
        artifact.setUpdatedAt(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        return artifact;
    }

    /** 评审裁决：只有 IN_REVIEW 可裁决；驳回必须带原因。 */
    @Transactional(rollbackFor = Exception.class)
    public AiArtifact decide(Long reviewerId, Long id, boolean approve, String reason) {
        AiArtifact artifact = requireArtifact(id);
        if (!STATUS_IN_REVIEW.equals(artifact.getStatus())) {
            throw new IllegalArgumentException("只有评审中的制品可以裁决（当前 "
                    + artifact.getStatus() + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (approve) {
            artifact.setStatus(STATUS_APPROVED);
            artifact.setRejectReason(null);
        } else {
            if (!StringUtils.hasText(reason)) {
                throw new IllegalArgumentException("驳回必须写明原因");
            }
            artifact.setStatus(STATUS_REJECTED);
            artifact.setRejectReason(truncate(reason.strip(), MAX_REASON));
        }
        artifact.setReviewerId(reviewerId);
        artifact.setDecidedAt(now);
        artifact.setUpdatedAt(now);
        artifactMapper.updateById(artifact);
        return artifact;
    }

    /** 项目下的制品；status 为空返回全部状态，按 id 降序。 */
    public List<AiArtifact> listByProject(Long projectId, String status) {
        if (projectId == null) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        LambdaQueryWrapper<AiArtifact> wrapper = new LambdaQueryWrapper<AiArtifact>()
                .eq(AiArtifact::getProjectId, projectId);
        appendStatus(wrapper, status);
        return artifactMapper.selectList(wrapper.orderByDesc(AiArtifact::getId));
    }

    /**
     * 待我评审：别人的制品（自己产出的自己不看）；status 为空返回全部状态，按 id 降序。
     * 「只看评审中」由调用方传 status=IN_REVIEW。
     */
    public List<AiArtifact> listMyReviews(Long userId, String status) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        LambdaQueryWrapper<AiArtifact> wrapper = new LambdaQueryWrapper<AiArtifact>()
                .ne(AiArtifact::getOwnerUserId, userId);
        appendStatus(wrapper, status);
        return artifactMapper.selectList(wrapper.orderByDesc(AiArtifact::getId));
    }

    /** 采纳：APPROVED → APPLIED，并在同一事务里回写一条项目事实（采纳即新事实）。 */
    @Transactional(rollbackFor = Exception.class)
    public AiArtifact apply(Long userId, Long id) {
        AiArtifact artifact = requireArtifact(id);
        if (!STATUS_APPROVED.equals(artifact.getStatus())) {
            throw new IllegalArgumentException("只有已通过的制品可以采纳（当前 "
                    + artifact.getStatus() + "）");
        }
        artifact.setStatus(STATUS_APPLIED);
        artifact.setUpdatedAt(LocalDateTime.now());
        artifactMapper.updateById(artifact);
        projectFactService.recordArtifactFact(
                artifact.getProjectId(), artifact.getId(), artifact.getTitle(), artifact.getContent());
        log.info("制品 {} 由用户 {} 采纳，已回写项目事实", artifact.getId(), userId);
        return artifact;
    }

    // ==================== 校验（包级可见，便于单测） ====================

    /** 类型白名单归一：小写/别名一律拒绝，避免自创类型。 */
    static String normalizeKind(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("制品类型不能为空（可选 " + KIND_HINT + "）");
        }
        String kind = raw.strip().toUpperCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("不支持的制品类型：" + raw + "（可选 " + KIND_HINT + "）");
        }
        return kind;
    }

    static String normalizeTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("制品标题不能为空");
        }
        return truncate(raw.strip(), MAX_TITLE);
    }

    /** evidence 必须是能解析出非空数组的 JSON（元素为 project_fact.id），否则拒绝。 */
    String normalizeEvidence(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException(EVIDENCE_REQUIRED);
        }
        String evidence = raw.strip();
        if (evidenceCount(evidence) <= 0) {
            throw new IllegalArgumentException(EVIDENCE_REQUIRED);
        }
        return evidence;
    }

    /** 数组里有效 id 的条数；解析失败或没有有效 id 都返回 0。 */
    int evidenceCount(String evidence) {
        if (!StringUtils.hasText(evidence)) {
            return 0;
        }
        try {
            JsonNode node = objectMapper.readTree(evidence);
            if (node == null || !node.isArray()) {
                return 0;
            }
            int count = 0;
            for (JsonNode item : node) {
                if (isFactId(item) || (item.isObject() && (isFactId(item.path("id"))
                        || isFactId(item.path("factId"))))) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isFactId(JsonNode node) {
        return node.isIntegralNumber() && node.asLong() > 0;
    }

    private static void appendStatus(LambdaQueryWrapper<AiArtifact> wrapper, String status) {
        if (StringUtils.hasText(status)) {
            wrapper.eq(AiArtifact::getStatus, status.strip().toUpperCase(Locale.ROOT));
        }
    }

    private AiArtifact requireArtifact(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("制品 ID 不能为空");
        }
        AiArtifact artifact = artifactMapper.selectById(id);
        if (artifact == null) {
            throw new IllegalArgumentException("制品不存在");
        }
        return artifact;
    }

    private static String trimToNull(String value, int max) {
        return StringUtils.hasText(value) ? truncate(value.strip(), max) : null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
