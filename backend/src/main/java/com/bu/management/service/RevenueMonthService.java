package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.RevenueMonthClose;
import com.bu.management.mapper.RevenueMonthCloseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 营收月结：完结标记存在即该月锁定（展示实际值，拒绝导入与预估编辑）。
 */
@Service
@RequiredArgsConstructor
public class RevenueMonthService {

    private final RevenueMonthCloseMapper monthCloseMapper;

    public boolean isClosed(String yearMonth) {
        return monthCloseMapper.selectCount(new LambdaQueryWrapper<RevenueMonthClose>()
                .eq(RevenueMonthClose::getYearMonth, yearMonth)) > 0;
    }

    public Set<String> closedMonths() {
        return monthCloseMapper.selectList(null).stream()
                .map(RevenueMonthClose::getYearMonth)
                .collect(Collectors.toSet());
    }

    public void close(String yearMonth, Long userId) {
        if (isClosed(yearMonth)) {
            return;
        }
        RevenueMonthClose close = new RevenueMonthClose();
        close.setYearMonth(yearMonth);
        close.setClosedAt(LocalDateTime.now());
        close.setClosedBy(userId);
        monthCloseMapper.insert(close);
    }

    public void reopen(String yearMonth) {
        monthCloseMapper.delete(new LambdaQueryWrapper<RevenueMonthClose>()
                .eq(RevenueMonthClose::getYearMonth, yearMonth));
    }

    public void assertNotClosed(String yearMonth) {
        if (isClosed(yearMonth)) {
            throw new IllegalStateException(yearMonth + " 已完结，如需重新导入请先取消完结");
        }
    }

    public List<RevenueMonthClose> list() {
        return monthCloseMapper.selectList(new LambdaQueryWrapper<RevenueMonthClose>()
                .orderByDesc(RevenueMonthClose::getYearMonth));
    }
}
