package com.bu.management.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
import com.bu.management.service.RevenueContractAssignment;
import com.bu.management.service.RevenueMappingResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 同步完成后的业务联动：新数据（或新映射）到位后，自动重扫仍处于待映射状态的行。
 * 只触碰 pending=1 且未人工锁定（mapping_locked≠1）的行；人工已确认的行不受影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncEventListener {

    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueWorklogEntryMapper worklogEntryMapper;
    private final RevenueCostEntryMapper costEntryMapper;
    private final RevenueMappingResolver mappingResolver;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;

    @EventListener
    public void onSyncCompleted(SyncCompletedEvent event) {
        try {
            switch (event.getDomain()) {
                case "contract" -> reResolvePendingContracts();
                case "worklog" -> reResolvePendingWorklogs(event.getScope());
                case "cost" -> reResolvePendingCosts(event.getScope());
                default -> {
                    // org/member 等域暂无下游重算
                }
            }
        } catch (RuntimeException ex) {
            // 联动失败不影响同步主流程，待下次同步或人工处理
            log.warn("同步联动处理失败 domain={} scope={}: {}", event.getDomain(), event.getScope(), ex.getMessage());
        }
    }

    /** 合同：按最新业务线/项目重跑归属判定，仍判不出的保持待映射 */
    private void reResolvePendingContracts() {
        List<RevenueContractEntry> pending = contractEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueContractEntry>()
                        .eq(RevenueContractEntry::getPending, 1)
                        .and(wrapper -> wrapper.isNull(RevenueContractEntry::getMappingLocked)
                                .or().ne(RevenueContractEntry::getMappingLocked, 1)));
        if (pending.isEmpty()) {
            return;
        }
        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1));
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full", (a, b) -> a));
        List<Project> projects = projectMapper.selectList(null);
        List<Long> enabledLineIds = lines.stream().map(BusinessLine::getId).toList();

        int resolved = 0;
        for (RevenueContractEntry entry : pending) {
            RevenueContractAssignment.Assigned assigned = RevenueContractAssignment.assign(
                    entry.getBrand(), entry.getBizLineRaw(), lines, lineMode, projects, enabledLineIds);
            if (!assigned.pending()) {
                entry.setBizLineId(assigned.lineId());
                entry.setProjectId(assigned.projectId());
                entry.setPending(0);
                contractEntryMapper.updateById(entry);
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("合同同步联动：{} 条待映射合同自动归属成功", resolved);
        }
    }

    /** 工时：映射记忆可能已补齐，重扫该月待映射行 */
    private void reResolvePendingWorklogs(String scope) {
        List<RevenueWorklogEntry> pending = worklogEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueWorklogEntry>()
                        .eq(RevenueWorklogEntry::getPending, 1)
                        .eq(StringUtils.hasText(scope), RevenueWorklogEntry::getYearMonth, scope));
        int resolved = 0;
        for (RevenueWorklogEntry entry : pending) {
            RevenueMappingResolver.Resolved r = mappingResolver.resolve(
                    entry.getBusinessLineName(), entry.getProjectNameRaw(), entry.getWorkNote());
            if (!r.pending()) {
                entry.setBusinessLineId(r.businessLineId());
                entry.setProjectId(r.projectId());
                entry.setWorkType(r.workType());
                entry.setSalesKind(r.salesKind());
                entry.setSalesProjectId(r.salesProjectId());
                entry.setPending(0);
                if ("pool".equals(r.salesKind())) {
                    entry.setTags(mappingResolver.tagWorkNote(entry.getWorkNote()));
                }
                worklogEntryMapper.updateById(entry);
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("工时同步联动：{} 条待映射工时自动归属成功", resolved);
        }
    }

    /** 成本：同工时逻辑 */
    private void reResolvePendingCosts(String scope) {
        List<RevenueCostEntry> pending = costEntryMapper.selectList(
                new LambdaQueryWrapper<RevenueCostEntry>()
                        .eq(RevenueCostEntry::getPending, 1)
                        .eq(StringUtils.hasText(scope), RevenueCostEntry::getYearMonth, scope));
        int resolved = 0;
        for (RevenueCostEntry entry : pending) {
            RevenueMappingResolver.Resolved r = mappingResolver.resolve(
                    entry.getBusinessLineName(), entry.getProjectNameRaw(), null);
            if (!r.pending()) {
                entry.setBusinessLineId(r.businessLineId());
                entry.setProjectId(r.projectId());
                entry.setWorkType(r.workType());
                entry.setSalesKind(r.salesKind());
                entry.setSalesProjectId(r.salesProjectId());
                entry.setPending(0);
                costEntryMapper.updateById(entry);
                resolved++;
            }
        }
        if (resolved > 0) {
            log.info("成本同步联动：{} 条待映射成本自动归属成功", resolved);
        }
    }
}
