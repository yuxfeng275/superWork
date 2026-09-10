package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.KpiAlertRuleRequest;
import com.bu.management.dto.KpiNoteRequest;
import com.bu.management.dto.KpiTargetRequest;
import com.bu.management.entity.KpiAlertRule;
import com.bu.management.entity.KpiDeviationNote;
import com.bu.management.entity.KpiTarget;
import com.bu.management.entity.KpiWeeklySnapshot;
import com.bu.management.mapper.KpiAlertRuleMapper;
import com.bu.management.mapper.KpiDeviationNoteMapper;
import com.bu.management.mapper.KpiTargetMapper;
import com.bu.management.mapper.KpiWeeklySnapshotMapper;
import com.bu.management.vo.KpiReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KpiReportService {

    private final KpiTargetMapper targetMapper;
    private final KpiWeeklySnapshotMapper snapshotMapper;
    private final KpiDeviationNoteMapper noteMapper;
    private final KpiAlertRuleMapper alertRuleMapper;
    private final KpiSnapshotService snapshotService;

    /** 年度周报：分组行（目标 + 最新快照 + 全年快照），含合计行 */
    public KpiReportVO buildReport(int year) {
        Map<String, KpiTarget> targets = targetMapper.selectList(new LambdaQueryWrapper<KpiTarget>()
                        .eq(KpiTarget::getYear, year))
                .stream().collect(Collectors.toMap(KpiTarget::getReportGroup, t -> t, (a, b) -> a));
        Map<Long, List<KpiDeviationNote>> notesBySnapshot = noteMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(KpiDeviationNote::getSnapshotId));

        KpiReportVO vo = new KpiReportVO();
        vo.setYear(year);
        List<KpiReportVO.GroupRow> groups = new ArrayList<>();
        KpiReportVO.GroupTotal total = new KpiReportVO.GroupTotal();
        total.setRevenueTarget(BigDecimal.ZERO);
        total.setProfitTarget(BigDecimal.ZERO);
        total.setYtdRevenue(BigDecimal.ZERO);
        total.setYtdProfit(BigDecimal.ZERO);
        total.setWeekDeltaRevenue(BigDecimal.ZERO);
        total.setWeekDeltaProfit(BigDecimal.ZERO);

        for (String group : snapshotService.reportGroupLines().keySet().stream().sorted().toList()) {
            KpiReportVO.GroupRow row = new KpiReportVO.GroupRow();
            row.setReportGroup(group);
            KpiTarget target = targets.get(group);
            if (target != null) {
                row.setRevenueTarget(target.getRevenueTarget());
                row.setProfitTarget(target.getProfitTarget());
            }
            List<KpiWeeklySnapshot> snapshots = snapshotMapper.selectList(new LambdaQueryWrapper<KpiWeeklySnapshot>()
                    .eq(KpiWeeklySnapshot::getReportGroup, group)
                    .ge(KpiWeeklySnapshot::getWeekEndDate, java.time.LocalDate.of(year, 1, 1))
                    .le(KpiWeeklySnapshot::getWeekEndDate, java.time.LocalDate.of(year, 12, 31))
                    .orderByAsc(KpiWeeklySnapshot::getWeekEndDate));
            List<KpiReportVO.SnapshotRow> rows = snapshots.stream()
                    .map(s -> toRow(s, target, notesBySnapshot.getOrDefault(s.getId(), List.of())))
                    .toList();
            row.setSnapshots(rows);
            row.setLatest(rows.isEmpty() ? null : rows.get(rows.size() - 1));
            groups.add(row);

            if (target != null) {
                total.setRevenueTarget(total.getRevenueTarget().add(nullToZero(target.getRevenueTarget())));
                total.setProfitTarget(total.getProfitTarget().add(nullToZero(target.getProfitTarget())));
            }
            if (row.getLatest() != null) {
                total.setYtdRevenue(total.getYtdRevenue().add(nullToZero(row.getLatest().getYtdRevenue())));
                total.setYtdProfit(total.getYtdProfit().add(nullToZero(row.getLatest().getYtdProfit())));
                total.setWeekDeltaRevenue(total.getWeekDeltaRevenue().add(nullToZero(row.getLatest().getWeekDeltaRevenue())));
                total.setWeekDeltaProfit(total.getWeekDeltaProfit().add(nullToZero(row.getLatest().getWeekDeltaProfit())));
            }
        }
        total.setRevenueRate(rate(total.getYtdRevenue(), total.getRevenueTarget()));
        total.setProfitRate(rate(total.getYtdProfit(), total.getProfitTarget()));
        vo.setGroups(groups);
        vo.setTotal(total);
        return vo;
    }

    private KpiReportVO.SnapshotRow toRow(KpiWeeklySnapshot snapshot, KpiTarget target, List<KpiDeviationNote> notes) {
        KpiReportVO.SnapshotRow row = new KpiReportVO.SnapshotRow();
        row.setSnapshotId(snapshot.getId());
        row.setWeekEndDate(snapshot.getWeekEndDate());
        row.setYtdRevenue(snapshot.getYtdRevenue());
        row.setYtdDirectCost(snapshot.getYtdDirectCost());
        row.setYtdLaborCost(snapshot.getYtdLaborCost());
        row.setYtdOtherCost(snapshot.getYtdOtherCost());
        row.setYtdProfit(snapshot.getYtdProfit());
        row.setWeekDeltaRevenue(snapshot.getWeekDeltaRevenue());
        row.setWeekDeltaProfit(snapshot.getWeekDeltaProfit());
        row.setEstimated(snapshot.getEstimated());
        row.setRevenueRate(rate(snapshot.getYtdRevenue(), target == null ? null : target.getRevenueTarget()));
        row.setProfitRate(rate(snapshot.getYtdProfit(), target == null ? null : target.getProfitTarget()));
        row.setNotes(notes.stream().map(note -> {
            KpiReportVO.NoteRow noteRow = new KpiReportVO.NoteRow();
            noteRow.setId(note.getId());
            noteRow.setMetric(note.getMetric());
            noteRow.setAlertLevel(note.getAlertLevel());
            noteRow.setDeviationReason(note.getDeviationReason());
            noteRow.setIsAbnormal(note.getIsAbnormal());
            noteRow.setCountermeasure(note.getCountermeasure());
            noteRow.setStatus(note.getStatus());
            noteRow.setUpdatedAt(note.getUpdatedAt());
            return noteRow;
        }).toList());
        return row;
    }

    private BigDecimal rate(BigDecimal value, BigDecimal target) {
        if (value == null || target == null || target.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return value.divide(target, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ 目标维护

    public List<KpiTarget> listTargets(int year) {
        return targetMapper.selectList(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getYear, year));
    }

    @Transactional
    public KpiTarget saveTarget(KpiTargetRequest request, Long userId) {
        if (request.getYear() == null || !StringUtils.hasText(request.getReportGroup())) {
            throw new IllegalArgumentException("年度与报表行分组不能为空");
        }
        snapshotService.assertValidGroup(request.getReportGroup());
        KpiTarget target = targetMapper.selectOne(new LambdaQueryWrapper<KpiTarget>()
                .eq(KpiTarget::getYear, request.getYear())
                .eq(KpiTarget::getReportGroup, request.getReportGroup()));
        if (target == null) {
            target = new KpiTarget();
            target.setYear(request.getYear());
            target.setReportGroup(request.getReportGroup());
            target.setCreatedBy(userId);
        }
        target.setRevenueTarget(nullToZero(request.getRevenueTarget()));
        target.setProfitTarget(nullToZero(request.getProfitTarget()));
        target.setRemark(request.getRemark());
        if (target.getId() == null) {
            targetMapper.insert(target);
        } else {
            targetMapper.updateById(target);
        }
        return target;
    }

    // ------------------------------------------------------------------ 备注维护

    @Transactional
    public KpiDeviationNote saveNote(Long noteId, KpiNoteRequest request, Long userId) {
        KpiDeviationNote note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new IllegalArgumentException("备注不存在");
        }
        if (request.getIsAbnormal() != null && Integer.valueOf(1).equals(request.getIsAbnormal())
                && !StringUtils.hasText(request.getCountermeasure())) {
            throw new IllegalArgumentException("判定为异常时必须填写对策");
        }
        note.setDeviationReason(request.getDeviationReason());
        note.setIsAbnormal(request.getIsAbnormal());
        note.setCountermeasure(request.getCountermeasure());
        note.setStatus(StringUtils.hasText(request.getDeviationReason()) ? "done" : "pending");
        note.setUpdatedBy(userId);
        noteMapper.updateById(note);
        return note;
    }

    // ------------------------------------------------------------------ 预警规则

    public List<KpiAlertRule> listRules() {
        return alertRuleMapper.selectList(null);
    }

    @Transactional
    public KpiAlertRule saveRule(KpiAlertRuleRequest request, Long userId) {
        String group = StringUtils.hasText(request.getReportGroup()) ? request.getReportGroup() : null;
        if (group != null) {
            snapshotService.assertValidGroup(group);
        }
        KpiAlertRule rule = group == null
                ? alertRuleMapper.selectOne(new LambdaQueryWrapper<KpiAlertRule>().isNull(KpiAlertRule::getReportGroup))
                : alertRuleMapper.selectOne(new LambdaQueryWrapper<KpiAlertRule>()
                        .eq(KpiAlertRule::getReportGroup, group));
        if (rule == null) {
            rule = new KpiAlertRule();
            rule.setReportGroup(group);
        }
        if (request.getWeeklyDivisor() != null) {
            rule.setWeeklyDivisor(request.getWeeklyDivisor());
        }
        if (request.getYellowRatio() != null) {
            rule.setYellowRatio(request.getYellowRatio());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        rule.setUpdatedBy(userId);
        if (rule.getId() == null) {
            alertRuleMapper.insert(rule);
        } else {
            alertRuleMapper.updateById(rule);
        }
        return rule;
    }
}
