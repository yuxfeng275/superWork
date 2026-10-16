package com.bu.management.service;

import com.bu.management.entity.WorktimeSyncLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 工时系统统一同步：把「合同明细 + 工时/成本 + 业务线利润月报」收敛成一次调用，
 * T+1 定时与手动同步按钮都走这里；项目利润是派生口径，随之自动对齐。
 *
 * <p>scope = 强制月份 YYYY-MM（可空）：
 * <ul>
 *   <li>空：合同取当年、工时/成本按工时系统已确认月份自动扫描、业务线利润刷新当年各月；</li>
 *   <li>YYYY-MM：合同取该年、工时/成本与业务线利润都只强制该月（用于补数/修正）。</li>
 * </ul>
 * 各子步骤失败只体现在对应日志的 status，方法本身不抛业务异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorktimeFullSyncService {

    private final WorktimeContractSyncService contractSyncService;
    private final WorktimeMonthlySyncService monthlySyncService;
    private final BusinessLineProfitService businessLineProfitService;

    public List<WorktimeSyncLog> sync(String scopeMonth, String triggeredBy) {
        String forceMonth = StringUtils.hasText(scopeMonth) ? scopeMonth.trim() : null;
        int year = forceMonth != null && forceMonth.length() >= 4
                ? Integer.parseInt(forceMonth.substring(0, 4))
                : LocalDate.now().getYear();
        List<WorktimeSyncLog> logs = new ArrayList<>();
        // 1) 合同明细（项目利润营收来源）
        logs.add(contractSyncService.syncContracts(year, triggeredBy));
        // 2) 工时明细 + 成本分析（指定月份强制该月，否则按已确认月份自动扫描）
        logs.addAll(monthlySyncService.syncConfirmedMonths(forceMonth, triggeredBy));
        // 3) 业务线利润月报镜像（合计行权威来源；指定月份只更该月，否则刷新当年各月）
        if (forceMonth != null) {
            logs.add(businessLineProfitService.syncMonth(forceMonth, triggeredBy));
        } else {
            logs.addAll(businessLineProfitService.syncYear(LocalDate.now().getYear(), triggeredBy));
        }
        return logs;
    }
}
