package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueFinancialReport;
import com.bu.management.entity.BizLineProfitReport;
import com.bu.management.mapper.BizLineProfitReportMapper;
import com.bu.management.mapper.RevenueFinancialReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报月度收入管理。按月份×业务线存储财报未税收入，
 * 用于与系统收入对齐：当系统收入 < 财报收入时，差额以「财务调节」补充。
 */
@Service
@RequiredArgsConstructor
public class RevenueFinancialReportService {

    private final RevenueFinancialReportMapper mapper;
    private final BizLineProfitReportMapper worktimeReportMapper;

    /** 查询某年某业务线的财报收入 */
    public List<RevenueFinancialReport> listByYearAndLine(int year, Long businessLineId) {
        return mapper.selectList(new LambdaQueryWrapper<RevenueFinancialReport>()
                .likeRight(RevenueFinancialReport::getYearMonth, year + "-")
                .eq(businessLineId != null, RevenueFinancialReport::getBusinessLineId, businessLineId)
                .orderByAsc(RevenueFinancialReport::getYearMonth));
    }

    /** 查询某年全部财报收入，按业务线分组，返回 {lineId: {monthStr: amount}} */
    public Map<Long, Map<String, BigDecimal>> loadYearMap(int year) {
        List<RevenueFinancialReport> list = mapper.selectList(new LambdaQueryWrapper<RevenueFinancialReport>()
                .likeRight(RevenueFinancialReport::getYearMonth, year + "-")
                .orderByAsc(RevenueFinancialReport::getYearMonth));
        Map<Long, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (RevenueFinancialReport r : list) {
            result.computeIfAbsent(r.getBusinessLineId(), k -> new LinkedHashMap<>())
                    .put(r.getYearMonth(), r.getRevenueAmount());
        }
        return result;
    }

    /**
     * 工时系统业务线利润报表的只读基准，按本系统业务线聚合月份收入。
     * 该数据来自同步镜像，不写入财报基准表。
     */
    public Map<Long, Map<String, BigDecimal>> loadWorktimeYearMap(int year) {
        List<BizLineProfitReport> list = worktimeReportMapper.selectList(
                new LambdaQueryWrapper<BizLineProfitReport>()
                        .likeRight(BizLineProfitReport::getYearMonth, year + "-")
                        .isNotNull(BizLineProfitReport::getBusinessLineId)
                        .orderByAsc(BizLineProfitReport::getYearMonth));
        Map<Long, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (BizLineProfitReport r : list) {
            if (r.getRevenue() == null) {
                continue;
            }
            result.computeIfAbsent(r.getBusinessLineId(), k -> new LinkedHashMap<>())
                    .put(r.getYearMonth(), r.getRevenue());
        }
        return result;
    }

    /** 批量保存（先删后插，按 year_month + business_line_id 唯一） */
    public void batchSave(List<RevenueFinancialReport> list) {
        for (RevenueFinancialReport r : list) {
            mapper.delete(new LambdaQueryWrapper<RevenueFinancialReport>()
                    .eq(RevenueFinancialReport::getYearMonth, r.getYearMonth())
                    .eq(RevenueFinancialReport::getBusinessLineId, r.getBusinessLineId()));
            mapper.insert(r);
        }
    }
}
