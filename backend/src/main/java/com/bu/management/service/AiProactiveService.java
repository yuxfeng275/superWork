package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiProactiveSuggestion;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.MeetingTodo;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiProactiveSuggestionMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.vo.BuKeyMatterView;
import com.bu.management.vo.EmailDigestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 助手主动建议：打开助手/工作台时按规则扫描业务数据，挑出「现在就该做的一件事」。
 *
 * <p>设计约束（见 docs/ai-proactivity-plan.md）：
 * <ul>
 *   <li>规则确定性优先，不引入新的模型调用；LLM 只负责用户点了「去做」之后的执行。</li>
 *   <li>建议必须可执行：每条都带 prompt 与目标页面，点击即进入对应动作。</li>
 *   <li>不打扰：(user_id, dedupe_key) 唯一，同一周期同类只提一次；用户忽略即终态。</li>
 *   <li>单一数据源异常降级为「本轮无该条建议」，不影响其它规则。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProactiveService {

    /** 同一用户两次扫描的最小间隔，避免每次刷新都打一遍数据源。 */
    static final Duration SCAN_INTERVAL = Duration.ofMinutes(10);
    /** 建议有效期：超过 7 天的未处理建议不再展示。 */
    static final int KEEP_DAYS = 7;

    private static final String KIND_WEEKLY = "weekly_report";
    private static final String KIND_KEY_MATTER = "key_matter_week";
    private static final String KIND_MEETING = "meeting_todos";
    private static final String KIND_EMAIL = "email_digest";

    /** 前端展示用。 */
    public record Suggestion(Long id, String kind, String title, String body, String prompt,
                             String targetPath, int score) {}

    private final AiProactiveSuggestionMapper suggestionMapper;
    private final WeeklyReportService weeklyReportService;
    private final BuKeyMatterService keyMatterService;
    private final MeetingMapper meetingMapper;
    private final MeetingTodoMapper meetingTodoMapper;
    private final EmailDigestService emailDigestService;

    private final Map<Long, Instant> lastScan = new ConcurrentHashMap<>();

    /** 当前用户待处理建议：必要时先扫描一轮。 */
    public List<Suggestion> list(Long userId) {
        scanIfStale(userId);
        LocalDateTime since = LocalDate.now().minusDays(KEEP_DAYS).atStartOfDay();
        List<AiProactiveSuggestion> rows = suggestionMapper.selectList(
                new LambdaQueryWrapper<AiProactiveSuggestion>()
                        .eq(AiProactiveSuggestion::getUserId, userId)
                        .eq(AiProactiveSuggestion::getStatus, AiProactiveSuggestion.STATUS_NEW)
                        .ge(AiProactiveSuggestion::getCreatedAt, since)
                        .orderByDesc(AiProactiveSuggestion::getScore)
                        .orderByDesc(AiProactiveSuggestion::getCreatedAt)
                        .last("LIMIT 5"));
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new Suggestion(row.getId(), row.getKind(), row.getTitle(), row.getBody(),
                        row.getPrompt(), row.getTargetPath(),
                        row.getScore() == null ? 0 : row.getScore()))
                .toList();
    }

    /** 用户点了「去做」：标记已处理，避免重复提示。 */
    public void accept(Long userId, Long id) {
        finish(userId, id, AiProactiveSuggestion.STATUS_DONE);
    }

    /** 用户点了「忽略」：本周期内不再提示（dedupe_key 已占位）。 */
    public void dismiss(Long userId, Long id) {
        finish(userId, id, AiProactiveSuggestion.STATUS_DISMISSED);
    }

    private void finish(Long userId, Long id, String status) {
        AiProactiveSuggestion row = suggestionMapper.selectById(id);
        if (row == null || !userId.equals(row.getUserId())) {
            throw new ResourceNotFoundException("建议不存在");
        }
        row.setStatus(status);
        suggestionMapper.updateById(row);
    }

    private void scanIfStale(Long userId) {
        Instant now = Instant.now();
        Instant last = lastScan.get(userId);
        if (last != null && Duration.between(last, now).compareTo(SCAN_INTERVAL) < 0) {
            return;
        }
        lastScan.put(userId, now);
        scan(userId);
    }

    /** 规则扫描一轮；单条规则失败只记日志。 */
    void scan(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        runRule(userId, today, () -> weeklyReportRule(userId, monday));
        runRule(userId, today, () -> keyMatterRule(userId, monday));
        runRule(userId, today, () -> meetingTodoRule(userId, monday));
        runRule(userId, today, () -> emailDigestRule(userId, today.minusDays(1)));
    }

    private void runRule(Long userId, LocalDate today, Rule rule) {
        try {
            Draft draft = rule.evaluate();
            if (draft != null) {
                save(userId, draft);
            }
        } catch (Exception e) {
            log.warn("AI 主动建议规则执行失败 userId={}: {}", userId, e.getMessage());
        }
    }

    // ==================== 规则 ====================

    /** 本周周报没有草稿（或上次生成失败）。 */
    private Draft weeklyReportRule(Long userId, LocalDate monday) {
        List<WeeklyReport> recent = weeklyReportService.history(6);
        WeeklyReport thisWeek = recent == null ? null : recent.stream()
                .filter(report -> monday.equals(report.getWeekStartDate()))
                .findFirst().orElse(null);
        if (thisWeek == null) {
            return new Draft(KIND_WEEKLY, "周报:" + monday,
                    "本周周报还没有草稿",
                    "周五 17:00 自动生成前，可以先让 AI 按固定模版起一版",
                    "用 weekly_report_facts 取本周事实，按固定模版起草周报四段与周会纪要，"
                            + "不确定的地方标注「待补充」而不是编造",
                    "/weekly-report?week=" + monday, 90);
        }
        if (WeeklyReport.STATUS_GENERATION_FAILED.equals(thisWeek.getStatus())) {
            String error = thisWeek.getGenerationError() == null ? "" : thisWeek.getGenerationError();
            return new Draft(KIND_WEEKLY, "周报失败:" + monday,
                    "本周周报上次生成失败",
                    StringUtils.hasText(error) ? error : "可直接重试生成",
                    "本周周报生成失败过，先用 weekly_report_facts 核对事实，再重新起草一版",
                    "/weekly-report?week=" + monday, 96);
        }
        // 本周周报已有草稿/已确认/已发布：清掉之前提过的「没草稿 / 生成失败」，避免让人做已完成的事
        dismissStale(userId, List.of("周报:" + monday, "周报失败:" + monday));
        return null;
    }

    /** 我负责、但这周还没更新进展的大事儿。 */
    private Draft keyMatterRule(Long userId, LocalDate monday) {
        List<BuKeyMatterView> matters = keyMatterService.meeting(monday);
        if (matters == null) {
            return null;
        }
        List<BuKeyMatterView> pending = matters.stream()
                .filter(matter -> userId.equals(matter.getOwnerId()))
                .filter(matter -> !matter.isCurrentWeekUpdated())
                .filter(matter -> !"已完成".equals(matter.getStatus()))
                .toList();
        if (pending.isEmpty()) {
            return null;
        }
        String titles = pending.stream().map(BuKeyMatterView::getTitle).limit(3)
                .reduce((a, b) -> a + "、" + b).orElse("");
        // 深链直接打开第一项，省掉「进列表再找」这一步
        String target = pending.get(0).getId() == null
                ? "/key-matters" : "/key-matters?matterId=" + pending.get(0).getId();
        return new Draft(KIND_KEY_MATTER, "大事儿:" + monday,
                pending.size() + " 个大事儿本周还没更新进展",
                titles + (pending.size() > 3 ? " 等" : ""),
                "列出我负责但本周未更新进展的大事儿，逐项给出「本周进展 / 问题风险 / 需协调 / 下一步」"
                        + "的草稿，事实不足的地方直接问我",
                target, 70 + pending.size());
    }

    /** 近 7 天会议纪要里还没转化的待办。 */
    private Draft meetingTodoRule(Long userId, LocalDate monday) {
        List<Meeting> meetings = meetingMapper.selectList(new LambdaQueryWrapper<Meeting>()
                .eq(Meeting::getOwnerUserId, userId)
                .ge(Meeting::getMeetingDate, monday.minusDays(7)));
        if (meetings == null || meetings.isEmpty()) {
            return null;
        }
        List<Long> ids = meetings.stream().map(Meeting::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return null;
        }
        Long drafts = meetingTodoMapper.selectCount(new LambdaQueryWrapper<MeetingTodo>()
                .in(MeetingTodo::getMeetingId, ids)
                .eq(MeetingTodo::getStatus, "DRAFT"));
        if (drafts == null || drafts == 0) {
            return null;
        }
        return new Draft(KIND_MEETING, "会议待办:" + monday,
                drafts + " 条会议待办还没转成任务",
                "会议纪要已生成，待办仍停留在草稿状态",
                "把这周会议纪要里的草稿待办逐条过一遍，说明每条建议转任务还是转事项、负责人与截止时间怎么定",
                "/meetings", 60 + Math.min(20, drafts.intValue()));
    }

    /** 昨日邮件摘要里的待办/风险还没处理。 */
    private Draft emailDigestRule(Long userId, LocalDate yesterday) {
        EmailDigestResponse digest = emailDigestService.getResponse(userId, yesterday);
        if (digest == null || digest.id() == null || digest.mailCount() == 0) {
            return null;
        }
        int todos = size(digest.todos());
        int risks = size(digest.risks());
        if (todos == 0 && risks == 0) {
            return null;
        }
        return new Draft(KIND_EMAIL, "邮件:" + yesterday,
                "昨日邮件摘要有 " + (todos + risks) + " 条待办与风险",
                "todos " + todos + " 条 · 风险 " + risks + " 条",
                "读一下昨天（" + yesterday + "）的邮件摘要，挑出最该今天处理的待办与风险，"
                        + "每条给一句结论和下一步动作，再问我要不要转成任务",
                "/emails", 55 + Math.min(20, todos + risks));
    }

    /** 同一周期条件已消失：把还挂着的建议置为忽略（dedupe_key 不复用，下轮不会再生成）。 */
    private void dismissStale(Long userId, List<String> dedupeKeys) {
        List<AiProactiveSuggestion> stale = suggestionMapper.selectList(
                new LambdaQueryWrapper<AiProactiveSuggestion>()
                        .eq(AiProactiveSuggestion::getUserId, userId)
                        .eq(AiProactiveSuggestion::getStatus, AiProactiveSuggestion.STATUS_NEW)
                        .in(AiProactiveSuggestion::getDedupeKey, dedupeKeys));
        if (stale == null || stale.isEmpty()) {
            return;
        }
        for (AiProactiveSuggestion row : stale) {
            row.setStatus(AiProactiveSuggestion.STATUS_DISMISSED);
            suggestionMapper.updateById(row);
        }
    }

    private static int size(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    // ==================== 落库 ====================

    private void save(Long userId, Draft draft) {
        boolean exists = suggestionMapper.selectCount(new LambdaQueryWrapper<AiProactiveSuggestion>()
                .eq(AiProactiveSuggestion::getUserId, userId)
                .eq(AiProactiveSuggestion::getDedupeKey, draft.dedupeKey())) > 0;
        if (exists) {
            return;
        }
        AiProactiveSuggestion row = new AiProactiveSuggestion();
        row.setUserId(userId);
        row.setKind(draft.kind());
        row.setDedupeKey(draft.dedupeKey());
        row.setTitle(cap(draft.title(), 200));
        row.setBody(cap(draft.body(), 500));
        row.setPrompt(cap(draft.prompt(), 1000));
        row.setTargetPath(draft.targetPath());
        row.setScore(draft.score());
        row.setStatus(AiProactiveSuggestion.STATUS_NEW);
        row.setCreatedAt(LocalDateTime.now());
        try {
            suggestionMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发扫描重复插入，忽略
        }
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private interface Rule {
        Draft evaluate();
    }

    private record Draft(String kind, String dedupeKey, String title, String body, String prompt,
                         String targetPath, int score) {}
}
