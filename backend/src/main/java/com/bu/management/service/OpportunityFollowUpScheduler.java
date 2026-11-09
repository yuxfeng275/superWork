package com.bu.management.service;

import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.entity.SalesOpportunity;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 商机跟进问句扫描：每个工作日早上把「今日该跟进 / 逾期 / 静默 / 报价静默」的商机捞出来问一句。
 *
 * <p>口径：
 * <ul>
 *   <li><b>先落库再推送</b>：先写 {@code opportunity_followup_prompt}（渠道 INAPP 兜底），再交给
 *       {@link OpportunityFollowUpSender} 发送（试点期优先企微机器人单聊，回退应用消息）；</li>
 *   <li><b>试点期一律单发给机器人创建人</b>，不发给商机负责人；每人每天最多 3 条问句，
 *       试点期按<b>实际接收人</b>计数，超了直接跳过（不补发、不排队）；</li>
 *   <li>任何异常都吞掉并降级，绝不影响落库与其它商机的提醒；</li>
 *   <li>执行时间取 {@code opportunity.followup.scan-cron}，默认工作日 09:20（Asia/Shanghai）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityFollowUpScheduler {

    /** 每人每天的问句上限。 */
    static final int DAILY_PROMPT_LIMIT = 3;
    /** 静默天数缺省值。 */
    static final int SILENT_DAYS = 7;
    /** 报价静默天数缺省值。 */
    static final int QUOTATION_SILENT_DAYS = 5;

    private final OpportunityFollowUpService followUpService;
    private final OpportunityFollowUpSender followUpSender;

    /** 一轮扫描：四条规则各自独立，单条失败只记日志。 */
    @Scheduled(cron = "${opportunity.followup.scan-cron:0 20 9 * * MON-FRI}", zone = "Asia/Shanghai")
    public void scan() {
        LocalDate today = LocalDate.now();
        dispatch(OpportunityFollowUpService.STAGE_DUE, () -> followUpService.dueToday(today));
        dispatch(OpportunityFollowUpService.STAGE_OVERDUE, () -> followUpService.overdue(today));
        dispatch(OpportunityFollowUpService.STAGE_SILENT, () -> followUpService.silent(silentDays()));
        dispatch(OpportunityFollowUpService.STAGE_QUOTATION_SILENT,
                () -> followUpService.quotationSilent(quotationSilentDays()));
    }

    private void dispatch(String stage, Supplier<List<SalesOpportunity>> source) {
        List<SalesOpportunity> candidates;
        try {
            candidates = source.get();
        } catch (Exception e) {
            log.warn("商机跟进扫描失败（{}）: {}", stage, e.getMessage());
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int sent = 0;
        for (SalesOpportunity opportunity : candidates) {
            try {
                if (handle(opportunity, stage)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("商机跟进问句下发失败: oppId={} stage={} err={}",
                        opportunity == null ? null : opportunity.getId(), stage, e.getMessage());
            }
        }
        log.info("商机跟进扫描完成: {} 命中 {} 条，下发 {} 条", stage, candidates.size(), sent);
    }

    /** 处理单个商机：配额 → 落库 → 发送（试点期机器人单聊，逐级降级）。 */
    private boolean handle(SalesOpportunity opportunity, String stage) {
        if (opportunity == null || opportunity.getId() == null) {
            return false;
        }
        if (Integer.valueOf(1).equals(opportunity.getRemindMuted())) {
            return false;
        }
        // 试点期实际接收人：机器人创建人；解析不出来时才退回商机负责人
        Long receiverUserId = followUpSender.pilotReceiverUserId();
        if (receiverUserId == null) {
            receiverUserId = opportunity.getOwnerUserId();
        }
        if (receiverUserId != null
                && followUpService.promptsSentToday(receiverUserId) >= DAILY_PROMPT_LIMIT) {
            return false;
        }
        OpportunityFollowupPrompt prompt = followUpService.createPrompt(
                opportunity.getId(), stage, OpportunityFollowUpService.CHANNEL_INAPP, receiverUserId);
        if (prompt == null || !OpportunityFollowupPrompt.STATE_SENT.equals(prompt.getState())) {
            // 已经问过并答过（同日同阶段幂等命中），不再打扰
            return false;
        }
        // 落库已完成，发送链内部逐级降级，绝不向上抛
        followUpSender.send(prompt);
        return true;
    }

    /** 静默天数：超过该天数没有任何跟进记录就提醒一次。 */
    private int silentDays() {
        return SILENT_DAYS;
    }

    /** 报价静默天数：报价发出后超过该天数没动静就提醒一次。 */
    private int quotationSilentDays() {
        return QUOTATION_SILENT_DAYS;
    }
}
