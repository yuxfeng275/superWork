package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.EmailMessageMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 事实层汇总口径（投递箱顶部的几个数字），供 {@code GET /api/projects/{projectId}/facts/summary} 使用。
 *
 * <p>说明：
 * <ul>
 *   <li>统计只覆盖当前用户可见的事实（走 {@link ProjectFactPolicy}），别人的 OWNER 事实不计入；</li>
 *   <li>{@code last24h} / {@code thisWeekZero} 用的是 {@code created_at}（「新增」口径），
 *       不是 {@code happened_at}；</li>
 *   <li>{@code ungrouped} 是<b>用户级</b>口径：「我名下还没归到任何项目的未分类邮件数」，
 *       条件为 {@code owner_user_id = 当前用户} 且 {@code project_id IS NULL} 且
 *       {@code grouping_status='UNGROUPED'}。分组流水线标 UNGROUPED 时会把 project_id 置空
 *       （见 {@code EmailProjectGroupingService#saveUngrouped}），所以按 project_id 查恒为 0，
 *       工作台会一直误报「未归类 = 0」。同一个「未归类」池子会出现在每个项目的投递箱里，
 *       这个数字<b>跨项目不能相加</b>，前端就是按单项目显示用的；统计异常时返回 null 而不是假装 0。</li>
 *   <li>{@code ungroupedFiles} 是<b>项目级</b>口径：本项目可见事实里 {@code FILE_UPLOAD}
 *       且「无正文或摘要过短」的条数，可跨项目相加；直接从已取到的事实内存里算，不额外查库。</li>
 *   <li>{@code isolated} 恒为 null：本机隔离计数只在 mac / Windows 客户端，服务端不掌握，
 *       前端显示占位，不要当 0 用。</li>
 *   <li>统计按 {@link #SUMMARY_PAGE_SIZE} 条一页翻到 {@link #SUMMARY_MAX_PAGES} 页——以前只拿最近 500 条当全量，
 *       单项目超 500 条就静默少计，还容易把「本周新增」误报成 0。翻满仍有余量时结果标
 *       {@code truncated=true}，此时所有数字都是<b>下限</b>，界面必须提示「统计未覆盖全部事实」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactSummaryService {

    /** 汇总分页大小：一页最多取这么多条事实，页内聚合在内存做。 */
    private static final int SUMMARY_PAGE_SIZE = 500;

    /** 汇总最多翻的页数：{@code 500 * 20 = 1 万条}，再多就停下并标 truncated。 */
    private static final int SUMMARY_MAX_PAGES = 20;

    /** 未分组邮件的 grouping_status 字面值。 */
    private static final String UNGROUPED = "UNGROUPED";

    /** 摘要过短阈值：与 ProjectFactService 的低质口径同档（只有标题、回查不到上下文的空壳）。 */
    private static final int THIN_DIGEST_MIN = 40;

    private final ProjectFactService factService;
    private final ProjectFactPolicy factPolicy;
    private final EmailMessageMapper emailMessageMapper;

    /**
     * 汇总结果。字段顺序即接口返回顺序，前端按名取值，不要随手调整。
     *
     * @param bySource      各来源类型条数
     * @param total         可见事实总条数
     * @param lowQuality    低质事实条数（body 空且 digest 短于 40）
     * @param last24h       最近 24 小时新增条数
     * @param ungrouped     我名下未归到任何项目的未分类邮件数（用户级、<b>跨项目不能相加</b>）；
     *                      统计失败为 null
     * @param ungroupedFiles 本项目投递的本机文件里「无正文或摘要过短」的条数（项目级、可跨项目相加）
     * @param isolated      本机隔离计数，服务端不掌握，恒为 null——前端显示占位，不要当 0 用
     * @param thisWeekZero  本周 0 新增时为 true
     * @param truncated     {@code true} 表示统计只覆盖前 {@code SUMMARY_PAGE_SIZE * SUMMARY_MAX_PAGES} 条，
     *                      各项数字都是<b>下限</b>，界面必须提示「统计未覆盖全部事实」，不能当准确值展示
     */
    public record FactsSummary(Map<String, Long> bySource, long total, long lowQuality,
                               long last24h, Long ungrouped, long ungroupedFiles, Long isolated,
                               boolean thisWeekZero, boolean truncated) {
    }

    /**
     * 汇总一个项目的事实（调用方必须先过 accessGuard.canRead）。
     *
     * <p>分页累加：每页先过 {@link ProjectFactPolicy#visible} 再累加到各口径。哪一页取不满一页就说明
     * 已经是最后一页；翻满 {@link #SUMMARY_MAX_PAGES} 页还有余量时把 {@code truncated} 置 true。
     * 翻页停不停由<b>原始</b>页大小决定（可见性过滤是后置的，offset 也是按原始行数算的）。
     */
    public FactsSummary summary(Long userId, Long projectId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dayAgo = now.minusHours(24);
        LocalDateTime weekStart = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        Map<String, Long> bySource = new LinkedHashMap<>();
        long total = 0;
        long lowQuality = 0;
        long last24h = 0;
        long thisWeek = 0;
        long ungroupedFiles = 0;
        boolean truncated = false;
        for (int page = 0; page < SUMMARY_MAX_PAGES; page++) {
            List<ProjectFact> raw = factService.query(projectId, null, null, SUMMARY_PAGE_SIZE,
                    page * SUMMARY_PAGE_SIZE);
            List<ProjectFact> facts = factPolicy.visible(userId, projectId, raw);
            total += facts.size();
            for (ProjectFact fact : facts) {
                String sourceType = fact.getSourceType() == null ? "UNKNOWN" : fact.getSourceType();
                bySource.merge(sourceType, 1L, Long::sum);
                if (!factService.qualityOf(fact)) {
                    lowQuality++;
                }
                if (isThinFileUpload(fact)) {
                    ungroupedFiles++;
                }
                LocalDateTime created = fact.getCreatedAt() == null ? fact.getHappenedAt() : fact.getCreatedAt();
                if (created != null && !created.isBefore(dayAgo)) {
                    last24h++;
                }
                if (created != null && !created.isBefore(weekStart)) {
                    thisWeek++;
                }
            }
            if (raw.size() < SUMMARY_PAGE_SIZE) {
                break;
            }
            if (page == SUMMARY_MAX_PAGES - 1) {
                truncated = true;
            }
        }
        return new FactsSummary(bySource, total, lowQuality, last24h,
                ungroupedCount(userId), ungroupedFiles, null, thisWeek == 0, truncated);
    }

    /**
     * 投递文件「没正文或摘要过短」的判定：只认 {@link ProjectFactService#SOURCE_FILE_UPLOAD}。
     *
     * <p>会议 / 邮件 / 工作项是系统自动汇入的，摘要长短不是投递质量问题；投递文件带了正文就算有料，
     * 没有正文时看 digest 长度（只有标题的空壳回查不到任何上下文）。直接用已经取到的事实判断，
     * 不为这个数字再打一次库。
     */
    private boolean isThinFileUpload(ProjectFact fact) {
        if (!ProjectFactService.SOURCE_FILE_UPLOAD.equals(fact.getSourceType())) {
            return false;
        }
        boolean bodyEmpty = !StringUtils.hasText(fact.getBody());
        int digestLength = fact.getDigest() == null ? 0 : fact.getDigest().strip().length();
        return bodyEmpty || digestLength < THIN_DIGEST_MIN;
    }

    /**
     * 我名下还没归到任何项目的未分类邮件数（<b>用户级</b>口径）。
     *
     * <p>pipeline 标 UNGROUPED 时会把 project_id 置空（{@code EmailProjectGroupingService#saveUngrouped}），
     * 所以按 project_id 查恒为 0；这里按 {@code owner_user_id} + {@code project_id IS NULL} +
     * {@code grouping_status} 统计。同一个「未归类」池子会出现在每个项目的投递箱里，
     * <b>跨项目不能相加</b>。
     *
     * <p>查不到（异常）就返回 null，让调用方区分「0」与「没统计到」。
     */
    private Long ungroupedCount(Long userId) {
        try {
            Long count = emailMessageMapper.selectCount(new LambdaQueryWrapper<EmailMessage>()
                    .eq(EmailMessage::getOwnerUserId, userId)
                    .isNull(EmailMessage::getProjectId)
                    .eq(EmailMessage::getGroupingStatus, UNGROUPED));
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            log.debug("事实汇总：用户 {} 未分类邮件统计失败，返回 null", userId);
            return null;
        }
    }
}
