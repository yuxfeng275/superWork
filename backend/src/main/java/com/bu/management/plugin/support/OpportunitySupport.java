package com.bu.management.plugin.support;

import com.bu.management.entity.SalesOpportunity;
import com.bu.management.service.OpportunityFollowUpService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商机工具的共用口径：阶段白名单、默认静默天数、行渲染。
 *
 * <p>内容与 AiAgentToolService 里的同名副本逐字一致——旧 switch 保留为实现副本，
 * 所以这里复制一份而不是反向依赖，避免「回退路径」被新代码牵着走。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class OpportunitySupport {

    /** 商机阶段白名单（与 OpportunityFollowUpParser 一致）。 */
    public static final Set<String> STAGES = Set.of(
            "初步接触", "需求确认", "方案报价", "商务谈判", "已成交");

    /** 商机跟进清单的默认静默天数。 */
    public static final int DEFAULT_SILENT_DAYS = 7;

    private OpportunitySupport() {
    }

    /** 按归属人收窄后追加一批商机行；同一商机只入第一个命中的桶。 */
    public static void appendDueRows(List<Map<String, Object>> rows, List<Long> seen, String bucket,
            List<SalesOpportunity> opportunities, Long userId, LocalDate today, int cap,
            OpportunityFollowUpService followUpService) {
        if (opportunities == null) {
            return;
        }
        for (SalesOpportunity opportunity : opportunities) {
            if (rows.size() >= cap) {
                return;
            }
            if (!followUpService.ownedBy(opportunity, userId) || seen.contains(opportunity.getId())) {
                continue;
            }
            seen.add(opportunity.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", bucket);
            row.putAll(opportunityRow(opportunity));
            long overdueDays = followUpService.overdueDays(opportunity, today);
            if (overdueDays > 0) {
                row.put("overdueDays", "逾期 " + overdueDays + " 天");
            }
            rows.add(row);
        }
    }

    /** 商机列表 → 行；逾期天数正数才带「逾期 N 天」。 */
    public static List<Map<String, Object>> opportunityRows(List<SalesOpportunity> opportunities,
            OpportunityFollowUpService followUpService) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (opportunities == null) {
            return rows;
        }
        LocalDate today = LocalDate.now();
        for (SalesOpportunity opportunity : opportunities) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.putAll(opportunityRow(opportunity));
            long overdueDays = followUpService.overdueDays(opportunity, today);
            if (overdueDays > 0) {
                row.put("overdueDays", "逾期 " + overdueDays + " 天");
            }
            rows.add(row);
        }
        return rows;
    }

    /** 一行商机：名称/客户/阶段/概率/下次跟进；不含金额与毛利。 */
    public static Map<String, Object> opportunityRow(SalesOpportunity opportunity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("opportunityId", opportunity.getId());
        row.put("name", opportunity.getName());
        row.put("customer", opportunity.getCustomer());
        row.put("stage", opportunity.getStatus());
        row.put("probability", opportunity.getProbability());
        row.put("nextFollowUpAt", opportunity.getNextFollowUpAt());
        row.put("lastFollowUpAt", opportunity.getLastFollowUpAt());
        return row;
    }

    /** 清单范围的中文标签。 */
    public static String rangeLabel(String range) {
        return switch (range) {
            case "today" -> "今日待跟进";
            case "overdue" -> "已逾期";
            case "silent" -> "长期未跟进";
            default -> "跟进中";
        };
    }
}
