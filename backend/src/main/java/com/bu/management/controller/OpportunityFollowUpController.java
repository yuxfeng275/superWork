package com.bu.management.controller;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.entity.OpportunityOwnerWecom;
import com.bu.management.entity.SalesOpportunity;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.entity.SysRole;
import com.bu.management.mapper.OpportunityOwnerWecomMapper;
import com.bu.management.service.OpportunityFollowUpService;
import com.bu.management.service.SysRoleService;
import com.bu.management.vo.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final OpportunityOwnerWecomMapper ownerWecomMapper;

    /**
     * 跟进看板一条记录。
     *
     * @param opportunity 商机快照
     * @param overdueDays 逾期天数（不逾期为 0）
     */
    public record BoardItem(SalesOpportunity opportunity, long overdueDays) {
    }

    /** 维护一条负责人→企微 userid 映射的请求体。 */
    public record OwnerWecomRequest(String ownerName, String wecomUserId, Integer enabled, String remark) {
    }

    /** 批量导入的一行（来源由调用方声明：MANUAL / CALENDAR / SESSION）。 */
    public record OwnerWecomImportItem(String ownerName, String wecomUserId, String source) {
    }

    /** 批量导入结果。 */
    public record OwnerWecomImportResult(int inserted, int updated, int skipped) {
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

    // ==================== 方案 B：负责人姓名 → 企微 userid 映射 ====================

    /** 列出全部负责人企微映射（按姓名排序，enabled=0 的也返回，便于运维看全貌）。 */
    @GetMapping("/followup/owner-wecom")
    public Result<List<OpportunityOwnerWecom>> listOwnerWecom(@RequestAttribute("userId") Long userId) {
        requireAdmin(userId);
        return Result.success(ownerWecomMapper.selectList(
                new LambdaQueryWrapper<OpportunityOwnerWecom>().orderByAsc(OpportunityOwnerWecom::getOwnerName)));
    }

    /** 新增或更新一条映射（按 ownerName 唯一键 upsert）；不允许改来源，来源沿用旧值。 */
    @PutMapping("/followup/owner-wecom")
    public Result<OpportunityOwnerWecom> saveOwnerWecom(@RequestAttribute("userId") Long userId,
                                                        @RequestBody OwnerWecomRequest request) {
        requireAdmin(userId);
        if (request == null || !StringUtils.hasText(request.ownerName())
                || !StringUtils.hasText(request.wecomUserId())) {
            throw new RuntimeException("负责人姓名与企微 userid 不能为空");
        }
        String ownerName = request.ownerName().strip();
        OpportunityOwnerWecom existing = findByOwnerName(ownerName);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            OpportunityOwnerWecom row = new OpportunityOwnerWecom();
            row.setOwnerName(ownerName);
            row.setWecomUserid(request.wecomUserId().strip());
            row.setEnabled(request.enabled() == null ? 1 : request.enabled());
            row.setSource(OpportunityOwnerWecom.SOURCE_MANUAL);
            row.setRemark(request.remark());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            ownerWecomMapper.insert(row);
            return Result.success(row);
        }
        existing.setWecomUserid(request.wecomUserId().strip());
        existing.setEnabled(request.enabled() == null ? existing.getEnabled() : request.enabled());
        existing.setRemark(request.remark());
        existing.setUpdatedAt(now);
        ownerWecomMapper.updateById(existing);
        return Result.success(existing);
    }

    /**
     * 批量导入映射（人工整理 / 日程参会人 / 机器人会话三类来源都是这个入口）。
     *
     * <p>按 ownerName 幂等：已存在的改 userid、来源与 enabled=1，不存在的新增；姓名或 userid 为空的记 skipped。
     */
    @PostMapping("/followup/owner-wecom/import")
    public Result<OwnerWecomImportResult> importOwnerWecom(@RequestAttribute("userId") Long userId,
                                                           @RequestBody List<OwnerWecomImportItem> items) {
        requireAdmin(userId);
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (OwnerWecomImportItem item : items == null ? List.<OwnerWecomImportItem>of() : items) {
            if (item == null || !StringUtils.hasText(item.ownerName())
                    || !StringUtils.hasText(item.wecomUserId())) {
                skipped++;
                continue;
            }
            String ownerName = item.ownerName().strip();
            String source = StringUtils.hasText(item.source())
                    ? item.source().strip().toUpperCase()
                    : OpportunityOwnerWecom.SOURCE_MANUAL;
            OpportunityOwnerWecom existing = findByOwnerName(ownerName);
            if (existing == null) {
                OpportunityOwnerWecom row = new OpportunityOwnerWecom();
                row.setOwnerName(ownerName);
                row.setWecomUserid(item.wecomUserId().strip());
                row.setEnabled(1);
                row.setSource(source);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                ownerWecomMapper.insert(row);
                inserted++;
            } else {
                existing.setWecomUserid(item.wecomUserId().strip());
                existing.setEnabled(1);
                existing.setSource(source);
                existing.setUpdatedAt(now);
                ownerWecomMapper.updateById(existing);
                updated++;
            }
        }
        return Result.success(new OwnerWecomImportResult(inserted, updated, skipped));
    }

    /** 按负责人姓名取一条映射（唯一键 uk_owner_name）。 */
    private OpportunityOwnerWecom findByOwnerName(String ownerName) {
        List<OpportunityOwnerWecom> rows = ownerWecomMapper.selectList(
                new LambdaQueryWrapper<OpportunityOwnerWecom>()
                        .eq(OpportunityOwnerWecom::getOwnerName, ownerName)
                        .last("limit 1"));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
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
