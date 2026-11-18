package com.bu.management.plugin.facts;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.PendingDeliveryService;
import com.bu.management.vo.PendingDeliveryVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 查询待交付合同：Agent 每月初据此汇总“本月待确认”，不做自动确认。
 *
 * <p>确认是财务写操作，必须由人执行；本能力只读，返回合同明细、金额、负责人、是否已确认。
 */
@Capability(id = "pending_delivery",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.READ,
        title = "查询待确认交付合同",
        whenToUse = "每月初汇总本月待交付/待确认合同，或用户问哪些合同还没确认时",
        inputSchema = """
                {"type":"object","properties":{"month":{"type":"string","description":"月份 YYYY-MM，默认当前月；传 all 看全年"}}}
                """,
        timeoutMs = 20000)
@Component
@RequiredArgsConstructor
public class PendingDeliveryCapability implements CapabilityHandler {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PendingDeliveryService pendingDeliveryService;
    private final ObjectMapper objectMapper;

    @Override
    public CapabilityResult execute(com.fasterxml.jackson.databind.JsonNode input, CapabilityContext ctx)
            throws Exception {
        String arg = ToolArgs.text(input, "month");
        String month = StringUtils.hasText(arg) ? arg.trim()
                : LocalDate.now(ZoneId.of("Asia/Shanghai")).format(MONTH);
        int year = "all".equalsIgnoreCase(month)
                ? LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear()
                : Integer.parseInt(month.substring(0, 4));
        PendingDeliveryVO vo = pendingDeliveryService.listYear(year);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("year", year);
        if ("all".equalsIgnoreCase(month)) {
            root.put("month", "all");
        } else {
            root.put("month", month);
            root.put("label", month + " 待确认交付合同");
        }
        ArrayNode entries = root.putArray("entries");
        long total = 0;
        if (vo.getGroups() != null) {
            for (PendingDeliveryVO.MonthGroup group : vo.getGroups()) {
                if (!"all".equalsIgnoreCase(month)
                        && (group.getMonth() == null || !group.getMonth().equals(month))) {
                    continue;
                }
                if (group.getEntries() == null) continue;
                for (PendingDeliveryVO.Entry e : group.getEntries()) {
                    if (!Boolean.TRUE.equals(e.getConfirmed())) {
                        ObjectNode row = entries.addObject();
                        row.put("id", e.getId());
                        row.put("contractNo", e.getContractNo());
                        row.put("contractName", e.getContractName());
                        row.put("customer", e.getCustomer());
                        row.put("amount", e.getReceivableAmount());
                        row.put("owner", e.getSalesOwner());
                        row.put("expectedMonth", e.getExpectedMonth());
                        row.put("overdue", Boolean.TRUE.equals(e.getOverdue()));
                        total++;
                    }
                }
            }
        }
        root.put("count", total);
        return ToolResults.json(objectMapper.writeValueAsString(root));
    }
}
