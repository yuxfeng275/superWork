package com.bu.management.service;

import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 合同明细归属判定（Excel 手工导入与工时系统自动同步共用）：
 * 先依据「收款款项类型」确定业务线，再仅在该业务线内按「品牌」匹配既有营收项目；禁止品牌跨业务线猜测。
 * 未命中项目时，aggregate/simple 业务线落业务线级行，full 业务线进入待映射清单；不静默丢弃。
 */
public final class RevenueContractAssignment {

    /** 品牌关键词 → 营收主项目名（关键词按长度优先匹配，包含即可命中） */
    private static final Map<String, String> BRAND_TO_PROJECT = Map.ofEntries(
            Map.entry("皇家宠物", "皇家项目"),
            Map.entry("皇家", "皇家项目"),
            Map.entry("speedo", "Speedo"),
            Map.entry("速比涛", "Speedo"),
            Map.entry("飞鹤", "飞鹤"),
            Map.entry("澳优", "澳优"),
            Map.entry("佳贝艾特", "澳优"),
            Map.entry("海普诺凯", "澳优"),
            Map.entry("佳贝", "澳优"),
            Map.entry("海普", "澳优"),
            Map.entry("逢时", "逢时"),
            Map.entry("黄天鹅", "黄天鹅"));

    private static final String[] TYPE_KEYWORDS = {"会员通", "精准", "saas", "定制", "短信"};

    private RevenueContractAssignment() {
    }

    public record Assigned(Long lineId, Long projectId, boolean pending) {
    }

    /** 归属：先按收款款项类型确定业务线，再仅在该线内按品牌匹配项目。 */
    public static Assigned assign(String brand, String typeRaw, List<BusinessLine> lines, Map<Long, String> lineMode,
                                  List<Project> projects, List<Long> enabledLineIds) {
        Long lineId = matchTypeLine(typeRaw, lines);
        if (lineId == null) {
            // 未知收款类型不得凭品牌跨线猜测。
            return new Assigned(null, null, true);
        }
        return assignToLine(brand, lineId, lineMode, projects, enabledLineIds);
    }

    /** 已知业务线（如工时系统映射表命中）时的项目归属。 */
    public static Assigned assignToLine(String brand, Long lineId, Map<Long, String> lineMode,
                                        List<Project> projects, List<Long> enabledLineIds) {
        String targetName = brandTarget(brand);
        if (targetName != null) {
            Project brandProject = projects.stream()
                    .filter(p -> p.getName() != null && p.getName().equalsIgnoreCase(targetName)
                            && Objects.equals(p.getBusinessLineId(), lineId)
                            && enabledLineIds.contains(p.getBusinessLineId()))
                    .findFirst().orElse(null);
            if (brandProject != null) {
                return new Assigned(lineId, brandProject.getId(), false);
            }
        }
        // 仅 full 模式（定制/SAAS）需严格映射到系统项目，无品牌命中才待确认；
        // 其余业务线（会员通/精准等）按业务线优先直接落业务线级，不再进入待映射。
        boolean needProject = "full".equals(lineMode.get(lineId));
        return new Assigned(lineId, null, needProject);
    }

    public static String brandTarget(String brand) {
        if (!StringUtils.hasText(brand)) {
            return null;
        }
        String lower = brand.toLowerCase(Locale.ROOT);
        String best = null;
        int bestLen = -1;
        for (Map.Entry<String, String> entry : BRAND_TO_PROJECT.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase(Locale.ROOT)) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    /**
     * 收款款项类型 → 业务线（会员通/精准/saas/定制 关键字）。
     * 短信充值类（如「全域-全渠道-全域京东文本短信」）固定归「精准」业务线。
     */
    public static Long matchTypeLine(String typeRaw, List<BusinessLine> lines) {
        if (!StringUtils.hasText(typeRaw)) {
            return null;
        }
        String lower = typeRaw.toLowerCase(Locale.ROOT);
        if (lower.contains("短信")) {
            return lines.stream()
                    .filter(line -> line.getName() != null && line.getName().contains("精准"))
                    .map(BusinessLine::getId)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
        String keyword = null;
        for (String candidate : TYPE_KEYWORDS) {
            if (lower.contains(candidate.toLowerCase(Locale.ROOT))) {
                keyword = candidate;
                break;
            }
        }
        if (keyword == null) {
            return null;
        }
        String finalKeyword = keyword;
        return lines.stream()
                .filter(line -> line.getName() != null
                        && line.getName().toLowerCase(Locale.ROOT).contains(finalKeyword.toLowerCase(Locale.ROOT)))
                .map(BusinessLine::getId)
                .sorted()
                .findFirst()
                .orElse(null);
    }
}
