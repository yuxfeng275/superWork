package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.DeliveryConfirmation;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.User;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.DeliveryConfirmationMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.PendingDeliveryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 待交付合同确认：delivery_date 为空的合同明细按预计交付月份（service_end_date 所在月）分组展示，
 * 支持按明细行批量确认/取消确认（确认粒度 = 合同明细行）。
 * <ul>
 *   <li>预计交付月份早于当月 → 逾期组（warning=overdue），仍落在其原始月份暴露；</li>
 *   <li>service_end_date 为空 → 未定月份组（warning=unknown），仅展示标记，不进项目利润月份预估块；</li>
 *   <li>已确认（revoked_at IS NULL）计入项目利润预估营收与利润；未确认仅展示不计利润。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingDeliveryService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final RevenueContractEntryMapper contractEntryMapper;
    private final DeliveryConfirmationMapper confirmationMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    /** 当年全部待交付合同明细（含逾期与未定月份），按预计交付月份分组 */
    public PendingDeliveryVO listYear(int year) {
        YearMonth current = YearMonth.now();
        String currentYm = current.format(MONTH_FMT);
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        List<RevenueContractEntry> entries = contractEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueContractEntry>()
                        .isNull(RevenueContractEntry::getDeliveryDate)
                        .and(w -> w.isNull(RevenueContractEntry::getServiceEndDate)
                                .or().between(RevenueContractEntry::getServiceEndDate, yearStart, yearEnd))
                        .orderByAsc(RevenueContractEntry::getServiceEndDate)
                        .orderByAsc(RevenueContractEntry::getId));

        PendingDeliveryVO vo = new PendingDeliveryVO();
        vo.setYear(year);
        vo.setCurrentMonth(currentYm);
        vo.setGroups(new ArrayList<>());
        if (entries.isEmpty()) {
            return vo;
        }

        Map<Long, DeliveryConfirmation> activeConfirmations = activeConfirmations(
                entries.stream().map(RevenueContractEntry::getId).toList());
        Map<Long, String> lineNames = businessLineMapper.selectList(null).stream()
                .collect(Collectors.toMap(BusinessLine::getId, BusinessLine::getName, (a, b) -> a));
        Map<Long, String> projectNames = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
        Map<Long, User> usersById = userMapper.selectList(null).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 按月分组：实际月份升序在前，未定月份（null）殿后
        Map<String, List<RevenueContractEntry>> byMonth = entries.stream()
                .collect(Collectors.groupingBy(e -> e.getServiceEndDate() == null
                        ? "" : e.getServiceEndDate().format(MONTH_FMT)));
        List<String> monthKeys = byMonth.keySet().stream()
                .filter(k -> !k.isEmpty()).sorted().collect(Collectors.toCollection(ArrayList::new));
        if (byMonth.containsKey("")) {
            monthKeys.add("");
        }

        for (String key : monthKeys) {
            List<RevenueContractEntry> groupEntries = byMonth.get(key);
            PendingDeliveryVO.MonthGroup group = new PendingDeliveryVO.MonthGroup();
            if (key.isEmpty()) {
                group.setMonth(null);
                group.setLabel("未定月份");
                group.setWarning("unknown");
            } else {
                group.setMonth(key);
                group.setLabel(key.substring(0, 4) + "年" + Integer.parseInt(key.substring(5)) + "月");
                group.setWarning(key.compareTo(currentYm) < 0 ? "overdue" : null);
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal confirmedTotal = BigDecimal.ZERO;
            int confirmedCount = 0;
            List<PendingDeliveryVO.Entry> vos = new ArrayList<>();
            for (RevenueContractEntry entry : groupEntries) {
                PendingDeliveryVO.Entry item = toVO(entry, key.isEmpty() ? null : key,
                        currentYm, activeConfirmations, lineNames, projectNames, usersById);
                BigDecimal amount = entry.getReceivableAmount() == null
                        ? BigDecimal.ZERO : entry.getReceivableAmount();
                total = total.add(amount);
                if (Boolean.TRUE.equals(item.getConfirmed())) {
                    confirmedTotal = confirmedTotal.add(amount);
                    confirmedCount++;
                }
                vos.add(item);
            }
            group.setEntries(vos);
            group.setEntryCount(vos.size());
            group.setConfirmedCount(confirmedCount);
            group.setTotalAmount(total);
            group.setConfirmedAmount(confirmedTotal);
            vo.getGroups().add(group);
        }
        return vo;
    }

    /** 批量确认（幂等）：已交付（delivery_date 已补录）的明细自动跳过；重复确认刷新确认人/时间 */
    @Transactional
    public int confirm(List<Long> entryIds, Long userId) {
        if (entryIds == null || entryIds.isEmpty()) {
            throw new IllegalArgumentException("entryIds 不能为空");
        }
        List<RevenueContractEntry> entries = contractEntryMapper.selectBatchIds(entryIds).stream()
                .filter(e -> e.getDeliveryDate() == null)
                .toList();
        if (entries.isEmpty()) {
            return 0;
        }
        Map<Long, DeliveryConfirmation> existing = confirmationMapper.selectList(
                        new LambdaQueryWrapper<DeliveryConfirmation>()
                                .in(DeliveryConfirmation::getContractEntryId,
                                        entries.stream().map(RevenueContractEntry::getId).toList()))
                .stream().collect(Collectors.toMap(DeliveryConfirmation::getContractEntryId,
                        Function.identity(), (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();
        for (RevenueContractEntry entry : entries) {
            String expectedMonth = entry.getServiceEndDate() == null
                    ? null : entry.getServiceEndDate().format(MONTH_FMT);
            DeliveryConfirmation row = existing.get(entry.getId());
            if (row == null) {
                row = new DeliveryConfirmation();
                row.setContractEntryId(entry.getId());
                row.setExpectedMonth(expectedMonth);
                row.setConfirmedBy(userId);
                row.setConfirmedAt(now);
                confirmationMapper.insert(row);
            } else {
                row.setExpectedMonth(expectedMonth);
                row.setConfirmedBy(userId);
                row.setConfirmedAt(now);
                row.setRevokedBy(null);
                row.setRevokedAt(null);
                confirmationMapper.updateById(row);
            }
        }
        return entries.size();
    }

    /** 批量取消确认（幂等）：仅影响当前生效的确认记录 */
    @Transactional
    public int revoke(List<Long> entryIds, Long userId) {
        if (entryIds == null || entryIds.isEmpty()) {
            throw new IllegalArgumentException("entryIds 不能为空");
        }
        List<DeliveryConfirmation> rows = confirmationMapper.selectList(
                new LambdaQueryWrapper<DeliveryConfirmation>()
                        .in(DeliveryConfirmation::getContractEntryId, entryIds)
                        .isNull(DeliveryConfirmation::getRevokedAt));
        LocalDateTime now = LocalDateTime.now();
        for (DeliveryConfirmation row : rows) {
            row.setRevokedBy(userId);
            row.setRevokedAt(now);
            confirmationMapper.updateById(row);
        }
        return rows.size();
    }

    /**
     * 项目利润预估口径的待交付合同：delivery_date 为空、service_end_date 非空且早于下一年、
     * 已完成业务线映射（pending=0）。service_end_date 为空的「未定月份」不返回（不进月份预估块）。
     */
    public List<RevenueContractEntry> forecastEntries(int year, java.util.Set<Long> managedLineIds) {
        if (managedLineIds == null || managedLineIds.isEmpty()) {
            return List.of();
        }
        return contractEntryMapper.selectList(new LambdaQueryWrapper<RevenueContractEntry>()
                .isNull(RevenueContractEntry::getDeliveryDate)
                .isNotNull(RevenueContractEntry::getServiceEndDate)
                .lt(RevenueContractEntry::getServiceEndDate, LocalDate.of(year + 1, 1, 1))
                .eq(RevenueContractEntry::getPending, 0)
                .in(RevenueContractEntry::getBizLineId, managedLineIds));
    }

    /** 当前生效的确认（revoked_at IS NULL），键 = 合同明细ID */
    public Map<Long, DeliveryConfirmation> activeConfirmations(List<Long> entryIds) {
        if (entryIds == null || entryIds.isEmpty()) {
            return Map.of();
        }
        return confirmationMapper.selectList(new LambdaQueryWrapper<DeliveryConfirmation>()
                        .in(DeliveryConfirmation::getContractEntryId, entryIds)
                        .isNull(DeliveryConfirmation::getRevokedAt))
                .stream().collect(Collectors.toMap(DeliveryConfirmation::getContractEntryId,
                        Function.identity(), (a, b) -> a));
    }

    private PendingDeliveryVO.Entry toVO(RevenueContractEntry entry, String expectedMonth, String currentYm,
                                         Map<Long, DeliveryConfirmation> confirmations,
                                         Map<Long, String> lineNames, Map<Long, String> projectNames,
                                         Map<Long, User> usersById) {
        PendingDeliveryVO.Entry item = new PendingDeliveryVO.Entry();
        item.setId(entry.getId());
        item.setContractNo(entry.getContractNo());
        item.setContractName(entry.getContractName());
        item.setCustomer(entry.getCustomer());
        item.setItemDesc(entry.getItemDesc());
        item.setReceivableAmount(entry.getReceivableAmount());
        item.setSalesOwner(entry.getSalesOwner());
        item.setServiceEndDate(entry.getServiceEndDate());
        item.setExpectedMonth(expectedMonth);
        item.setBusinessLineName(entry.getBizLineId() == null ? null : lineNames.get(entry.getBizLineId()));
        item.setProjectName(entry.getProjectId() == null ? null : projectNames.get(entry.getProjectId()));
        item.setOverdue(expectedMonth != null && expectedMonth.compareTo(currentYm) < 0);
        DeliveryConfirmation confirmation = confirmations.get(entry.getId());
        item.setConfirmed(confirmation != null);
        if (confirmation != null) {
            item.setConfirmedAt(confirmation.getConfirmedAt());
            User confirmer = confirmation.getConfirmedBy() == null
                    ? null : usersById.get(confirmation.getConfirmedBy());
            if (confirmer != null) {
                item.setConfirmedByName(StringUtils.hasText(confirmer.getRealName())
                        ? confirmer.getRealName() : confirmer.getUsername());
            }
        }
        return item;
    }

    /** 明细行归属月份（预估口径）：服务结束月；早于当月视为当月（逾期即期交付）。与 ProjectProfitService 一致 */
    static String effectiveMonth(RevenueContractEntry entry, YearMonth current) {
        Objects.requireNonNull(entry.getServiceEndDate());
        YearMonth end = YearMonth.from(entry.getServiceEndDate());
        return end.isBefore(current) ? current.format(MONTH_FMT) : end.format(MONTH_FMT);
    }
}
