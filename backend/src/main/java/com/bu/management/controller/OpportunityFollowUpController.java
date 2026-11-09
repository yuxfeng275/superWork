package com.bu.management.controller;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.SysRole;
import com.bu.management.service.OpportunityFollowUpService;
import com.bu.management.service.SysRoleService;
import com.bu.management.vo.Result;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商机跟进闭环的站内接口：跟进看板、静音、问句确认。
 *
 * <p>看板的 {@code range} 只认三个值：{@code today} / {@code overdue} / {@code silent}；
 * 逾期天数服务端算好，前端只负责展示「逾期 N 天」。
 */
@RestController
@RequestMapping("/api/opportunities")
@RequiredArgsConstructor
public class OpportunityFollowUpController {

    /** 静默天数口径：超过 7 天没跟进就算静默。 */
    private static final int SILENT_DAYS = 7;

    private final OpportunityFollowUpService service;
    private final SysRoleService sysRoleService;

    /**
     * 跟进看板一条记录。
     *
     * @param opportunity 商机快照
     * @param overdueDays 逾期天数（不逾期为 0）
     */
    public record BoardItem(SalesOpportunity opportunity, long overdueDays) {
    }

    /** 跟进看板：today=今天该跟进，overdue=已逾期，silent=长期静默。 */
    @GetMapping("/followup/board")
    public Result<List<BoardItem>> board(@RequestParam(required = false, defaultValue = "today") String range) {
        LocalDate today = LocalDate.now();
        List<SalesOpportunity> rows = switch (range == null ? "today" : range) {
            case "overdue" -> service.overdue(today);
            case "silent" -> service.silent(SILENT_DAYS);
            default -> service.dueToday(today);
        };
        return Result.success(rows.stream()
                .map(row -> new BoardItem(row, service.overdueDays(row, today)))
                .toList());
    }

    /** 商机的问句台账（最近在前）。 */
    @GetMapping("/{id}/followup-prompts")
    public Result<List<OpportunityFollowupPrompt>> prompts(@PathVariable Long id) {
        return Result.success(service.listPrompts(id));
    }

    /** 静音/恢复这个商机的跟进提醒。 */
    @PostMapping("/{id}/mute")
    public Result<Void> mute(@PathVariable Long id,
                             @RequestParam(required = false, defaultValue = "true") boolean muted) {
        service.mute(id, muted);
        return Result.success();
    }

    /** 确认一条问句的解析结果：写跟进记录并更新商机快照。 */
    @PostMapping("/{id}/followup-drafts/{promptId}/confirm")
    public Result<SalesOpportunityFollowUp> confirm(@PathVariable Long id,
                                                    @PathVariable Long promptId,
                                                    @RequestParam(required = false) Long operatorUserId) {
        // 校验路径上的商机存在（问句本身按 promptId 定位，避免前端传错 id 时静默改到别的商机）
        service.get(id);
        return Result.success(service.confirmDraft(promptId, operatorUserId));
    }

    /**
     * 一次性回填 {@code next_follow_up_at}（上线后手工触发一次，之后不用再调）。
     *
     * <p>此前只有解析器没有批量入口，导致 {@code next_follow_up_at} 全表为空、「今日应跟 / 已逾期」
     * 永远命中 0 条。幂等：只扫还没填的行，重复调用不会覆盖已有时间。
     *
     * <p>仅管理岗位可调（{@link PositionRoles#isAdminRole}）：这是改数据面的批量写，不对普通销售开放。
     *
     * @return {scanned, filled, skipped}
     */
    @PostMapping("/followup/backfill")
    public Result<OpportunityFollowUpService.BackfillResult> backfill(@RequestAttribute("userId") Long userId) {
        requireAdmin(userId);
        return Result.success(service.backfillNextFollowUpAt());
    }

    private void requireAdmin(Long userId) {
        List<SysRole> roles = sysRoleService.getRolesByUserId(userId);
        boolean admin = roles != null && roles.stream()
                .map(SysRole::getCode)
                .anyMatch(PositionRoles::isAdminRole);
        if (!admin) {
            throw new RuntimeException("仅管理岗位可执行下次跟进时间回填");
        }
    }
}
