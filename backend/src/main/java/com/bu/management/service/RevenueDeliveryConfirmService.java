package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueDeliveryConfirmation;
import com.bu.management.entity.User;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueDeliveryConfirmationMapper;
import com.bu.management.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 按月待交付统计与销售确认：
 * 待交付口径 = delivery_date 为空或晚于今天（与实体注释「已交付判定实时用 delivery_date <= 今天」一致），
 * 月份维度取 sale_month（收款销售月份）。
 * 统计按 月份 × 业务线 × 销售 聚合；确认记录由管理人员与销售一一沟通后人工录入，可写备注。
 */
@Service
@RequiredArgsConstructor
public class RevenueDeliveryConfirmService {

    /** 源数据缺销售姓名时的占位键（唯一键不能为 NULL 的替代） */
    public static final String UNSET_SALES = "_UNSET_";

    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final RevenueContractEntryMapper entryMapper;
    private final RevenueDeliveryConfirmationMapper confirmMapper;
    private final BusinessLineMapper businessLineMapper;
    private final UserMapper userMapper;

    public record ConfirmRequest(String yearMonth, Long bizLineId, String salesOwner,
                                 String status, String remark) {}

    /** 统计行：月份×业务线×销售 的待交付聚合 + 确认状态。 */
    public record StatsRow(String yearMonth, Long bizLineId, String bizLineName, String salesOwner,
                           long entryCount, BigDecimal pendingAmount,
                           String status, String remark, String confirmedByName, LocalDateTime confirmedAt) {}

    /** 明细行：某 月份×业务线×销售 下的待交付合同。 */
    public record EntryRow(Long id, String contractNo, String contractName, String customer,
                           String itemDesc, BigDecimal receivableAmount, String saleMonth,
                           LocalDate deliveryDate) {}

    // ==================== 统计 ====================

    public List<StatsRow> stats(Integer year) {
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        LocalDate today = LocalDate.now();
        List<RevenueContractEntry> entries = entryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .select(RevenueContractEntry::getId, RevenueContractEntry::getSaleMonth,
                        RevenueContractEntry::getBizLineId, RevenueContractEntry::getSalesOwner,
                        RevenueContractEntry::getReceivableAmount, RevenueContractEntry::getDeliveryDate)
                .likeRight(RevenueContractEntry::getSaleMonth, targetYear + "-"));

        Map<Long, String> lineNames = new LinkedHashMap<>();
        for (BusinessLine line : businessLineMapper.selectList(null)) {
            lineNames.put(line.getId(), line.getName());
        }
        Map<String, RevenueDeliveryConfirmation> confirmations = confirmationsByKey(targetYear);

        // 聚合键：month|lineId|salesKey
        Map<String, StatsRow> grouped = new LinkedHashMap<>();
        for (RevenueContractEntry entry : entries) {
            if (!isPendingDelivery(entry, today)) continue;
            String month = entry.getSaleMonth();
            Long lineId = entry.getBizLineId();
            String salesKey = StringUtils.hasText(entry.getSalesOwner()) ? entry.getSalesOwner().trim() : UNSET_SALES;
            String key = month + "|" + lineId + "|" + salesKey;
            StatsRow row = grouped.get(key);
            if (row == null) {
                RevenueDeliveryConfirmation confirm = confirmations.get(key);
                row = new StatsRow(month, lineId,
                        lineId == null ? "未映射" : lineNames.getOrDefault(lineId, "业务线#" + lineId),
                        UNSET_SALES.equals(salesKey) ? "未标注销售" : salesKey,
                        0, BigDecimal.ZERO,
                        confirm == null ? RevenueDeliveryConfirmation.STATUS_PENDING : confirm.getStatus(),
                        confirm == null ? null : confirm.getRemark(),
                        confirm == null ? null : confirm.getConfirmedByName(),
                        confirm == null ? null : confirm.getConfirmedAt());
                grouped.put(key, row);
            }
            BigDecimal amount = entry.getReceivableAmount() == null ? BigDecimal.ZERO : entry.getReceivableAmount();
            grouped.put(key, new StatsRow(row.yearMonth(), row.bizLineId(), row.bizLineName(), row.salesOwner(),
                    row.entryCount() + 1, row.pendingAmount().add(amount),
                    row.status(), row.remark(), row.confirmedByName(), row.confirmedAt()));
        }
        List<StatsRow> rows = new ArrayList<>(grouped.values());
        rows.sort(Comparator.comparing(StatsRow::yearMonth)
                .thenComparing(r -> r.bizLineId() == null ? Long.MAX_VALUE : r.bizLineId())
                .thenComparing(StatsRow::salesOwner));
        return rows;
    }

    /** 某 月份×业务线×销售 的待交付明细。 */
    public List<EntryRow> entries(String yearMonth, Long bizLineId, String salesOwner) {
        requireMonth(yearMonth);
        LocalDate today = LocalDate.now();
        String salesKey = StringUtils.hasText(salesOwner) ? salesOwner.trim() : UNSET_SALES;
        return entryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                        .eq(RevenueContractEntry::getSaleMonth, yearMonth)
                        .eq(bizLineId != null, RevenueContractEntry::getBizLineId, bizLineId)
                        .isNull(bizLineId == null, RevenueContractEntry::getBizLineId)
                        .orderByAsc(RevenueContractEntry::getContractNo))
                .stream()
                .filter(entry -> isPendingDelivery(entry, today))
                .filter(entry -> salesKey.equals(StringUtils.hasText(entry.getSalesOwner())
                        ? entry.getSalesOwner().trim() : UNSET_SALES))
                .map(entry -> new EntryRow(entry.getId(), entry.getContractNo(), entry.getContractName(),
                        entry.getCustomer(), entry.getItemDesc(), entry.getReceivableAmount(),
                        entry.getSaleMonth(), entry.getDeliveryDate()))
                .toList();
    }

    // ==================== 确认 ====================

    /** 人为确认（upsert）：可交付 / 无法按期交付 / 重置待确认，备注记录沟通结论。 */
    public RevenueDeliveryConfirmation confirm(ConfirmRequest request, Long userId) {
        requireMonth(request.yearMonth());
        if (request.bizLineId() == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        String status = request.status() == null ? "" : request.status().trim().toUpperCase();
        if (!List.of(RevenueDeliveryConfirmation.STATUS_PENDING,
                RevenueDeliveryConfirmation.STATUS_CONFIRMABLE,
                RevenueDeliveryConfirmation.STATUS_UNCONFIRMABLE).contains(status)) {
            throw new IllegalArgumentException("确认状态仅支持 PENDING / CONFIRMABLE / UNCONFIRMABLE");
        }
        String remark = StringUtils.hasText(request.remark()) ? request.remark().trim() : null;
        if (remark != null && remark.length() > 500) {
            throw new IllegalArgumentException("备注不能超过 500 字");
        }
        String salesKey = StringUtils.hasText(request.salesOwner()) ? request.salesOwner().trim() : UNSET_SALES;

        RevenueDeliveryConfirmation entity = confirmMapper.selectOne(
                new LambdaQueryWrapper<RevenueDeliveryConfirmation>()
                        .eq(RevenueDeliveryConfirmation::getYearMonth, request.yearMonth())
                        .eq(RevenueDeliveryConfirmation::getBizLineId, request.bizLineId())
                        .eq(RevenueDeliveryConfirmation::getSalesOwner, salesKey));
        if (entity == null) {
            entity = new RevenueDeliveryConfirmation();
            entity.setYearMonth(request.yearMonth());
            entity.setBizLineId(request.bizLineId());
            entity.setSalesOwner(salesKey);
        }
        entity.setStatus(status);
        entity.setRemark(remark);
        entity.setConfirmedBy(userId);
        entity.setConfirmedByName(resolveUserName(userId));
        entity.setConfirmedAt(LocalDateTime.now());
        if (entity.getId() == null) {
            confirmMapper.insert(entity);
        } else {
            confirmMapper.updateById(entity);
        }
        return entity;
    }

    // ==================== 内部 ====================

    private boolean isPendingDelivery(RevenueContractEntry entry, LocalDate today) {
        return entry.getDeliveryDate() == null || entry.getDeliveryDate().isAfter(today);
    }

    private Map<String, RevenueDeliveryConfirmation> confirmationsByKey(int year) {
        Map<String, RevenueDeliveryConfirmation> map = new LinkedHashMap<>();
        for (RevenueDeliveryConfirmation confirm : confirmMapper.selectList(
                new LambdaQueryWrapper<RevenueDeliveryConfirmation>()
                        .likeRight(RevenueDeliveryConfirmation::getYearMonth, year + "-"))) {
            map.put(confirm.getYearMonth() + "|" + confirm.getBizLineId() + "|" + confirm.getSalesOwner(), confirm);
        }
        return map;
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
    }

    private static void requireMonth(String yearMonth) {
        if (!StringUtils.hasText(yearMonth) || !MONTH_PATTERN.matcher(yearMonth.trim()).matches()) {
            throw new IllegalArgumentException("月份格式必须是 YYYY-MM");
        }
    }
}
