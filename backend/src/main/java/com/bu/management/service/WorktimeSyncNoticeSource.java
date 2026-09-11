package com.bu.management.service;

import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 工时系统同步健康通知源：最近一次合同同步失败或超过 26 小时未成功时提醒
 * （面向有 kpi:manage 权限的管理者；普通用户无感知）。
 * 工时系统不可用属于基础设施问题，查询失败一律降级为无通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorktimeSyncNoticeSource {

    public static final String KIND = "WORKTIME_SYNC_FAILED";
    /** 合同同步每日 06:30 跑；超 26h 无成功视为中断。 */
    private static final Duration STALE_AFTER = Duration.ofHours(26);

    private final WorktimeSyncLogMapper syncLogMapper;
    private final SysRoleService sysRoleService;

    public AiNoticeService.Notice compute(Long userId, LocalDate today) {
        try {
            List<String> permissions = sysRoleService.getPermissionCodesByUserId(userId);
            if (permissions == null || !permissions.contains("kpi:manage")) {
                return null;
            }
            WorktimeSyncLog last = syncLogMapper.selectOne(new LambdaQueryWrapper<WorktimeSyncLog>()
                    .eq(WorktimeSyncLog::getSyncType, "contract")
                    .orderByDesc(WorktimeSyncLog::getId)
                    .last("LIMIT 1"));
            if (last == null) {
                return notice(today, "尚未执行过同步");
            }
            if ("failed".equals(last.getStatus())) {
                return notice(today, "最近一次同步失败：" + (last.getMessage() == null ? "未知原因" : last.getMessage()));
            }
            if (last.getStartedAt() != null
                    && Duration.between(last.getStartedAt(), LocalDateTime.now()).compareTo(STALE_AFTER) > 0) {
                return notice(today, "超过 26 小时无成功同步，最近一次：" + last.getStartedAt());
            }
            return null;
        } catch (Exception e) {
            log.warn("工时同步健康通知计算失败: {}", e.getMessage());
            return null;
        }
    }

    private AiNoticeService.Notice notice(LocalDate today, String detail) {
        return new AiNoticeService.Notice(KIND,
                "工时系统同步异常",
                "合同数据自动同步异常：" + detail + "。可在「KPI周报 → 数据同步」查看详情，或让 AI 助手查询同步状态。",
                "/kpi-report",
                today,
                false);
    }
}
