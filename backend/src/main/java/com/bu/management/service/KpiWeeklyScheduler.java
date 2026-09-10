package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.KpiDeviationNote;
import com.bu.management.entity.KpiWeeklySnapshot;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.KpiDeviationNoteMapper;
import com.bu.management.mapper.KpiWeeklySnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * KPI 周快照定时任务：每周日 18:00 生成当周快照并扫描预警；
 * 若存在待填写偏差备注且配置了企业微信通知人（kpi.notify-wecom-user），推送提醒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KpiWeeklyScheduler {

    private final KpiSnapshotService snapshotService;
    private final KpiWeeklySnapshotMapper snapshotMapper;
    private final KpiDeviationNoteMapper noteMapper;
    private final WeComClient weComClient;

    @Value("${kpi.notify-wecom-user:}")
    private String notifyWeComUser;

    @Scheduled(cron = "${kpi.snapshot-cron:0 0 18 * * SUN}", zone = "Asia/Shanghai")
    public void weeklySnapshot() {
        LocalDate today = LocalDate.now();
        try {
            List<KpiWeeklySnapshot> snapshots = snapshotService.runWeeklySnapshot(today, null);
            log.info("KPI 周快照生成完成：{} 个分组", snapshots.size());
            notifyPendingNotes(today);
        } catch (RuntimeException ex) {
            log.error("KPI 周快照生成失败", ex);
        }
    }

    private void notifyPendingNotes(LocalDate weekEndDate) {
        if (!StringUtils.hasText(notifyWeComUser)) {
            return;
        }
        List<KpiWeeklySnapshot> snapshots = snapshotMapper.selectList(new LambdaQueryWrapper<KpiWeeklySnapshot>()
                .eq(KpiWeeklySnapshot::getWeekEndDate, weekEndDate));
        if (snapshots.isEmpty()) {
            return;
        }
        List<Long> snapshotIds = snapshots.stream().map(KpiWeeklySnapshot::getId).toList();
        Long pending = noteMapper.selectCount(new LambdaQueryWrapper<KpiDeviationNote>()
                .in(KpiDeviationNote::getSnapshotId, snapshotIds)
                .eq(KpiDeviationNote::getStatus, "pending"));
        if (pending == null || pending == 0) {
            return;
        }
        try {
            weComClient.pushText(notifyWeComUser,
                    "【KPI周报】本周存在 " + pending + " 条环比异常待填写偏差备注，请及时到系统「KPI周报」页处理。");
        } catch (RuntimeException ex) {
            log.warn("KPI 待办提醒推送失败: {}", ex.getMessage());
        }
    }
}
