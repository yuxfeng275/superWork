package com.bu.management.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.DataSyncLog;
import com.bu.management.entity.SyncTask;
import com.bu.management.mapper.DataSyncLogMapper;
import com.bu.management.mapper.SyncTaskMapper;
import com.bu.management.sync.collector.DataCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一同步编排器：定时与手动触发都经由此处。
 * 负责：任务校验 → 调用采集器 → 统一落 data_sync_log → 更新任务状态 → 发布 {@link SyncCompletedEvent}。
 */
@Slf4j
@Service
public class SyncOrchestrator {

    private final SyncTaskMapper taskMapper;
    private final DataSyncLogMapper syncLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, DataCollector> collectors = new HashMap<>();

    public SyncOrchestrator(SyncTaskMapper taskMapper,
                            DataSyncLogMapper syncLogMapper,
                            ApplicationEventPublisher eventPublisher,
                            List<DataCollector> collectorList) {
        this.taskMapper = taskMapper;
        this.syncLogMapper = syncLogMapper;
        this.eventPublisher = eventPublisher;
        for (DataCollector collector : collectorList) {
            collectors.put(collector.taskCode(), collector);
        }
    }

    /**
     * 执行同步任务。
     *
     * @param taskCode    sync_task.task_code
     * @param scope       同步范围（语义由采集器定义），可空
     * @param triggeredBy schedule / manual / fallback-import
     * @param operatorId  手动触发人，定时为 null
     * @return 本次写入的同步日志
     */
    public List<DataSyncLog> run(String taskCode, String scope, String triggeredBy, Long operatorId) {
        SyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTaskCode, taskCode));
        if (task == null) {
            throw new IllegalArgumentException("同步任务不存在: " + taskCode);
        }
        boolean scheduled = "schedule".equals(triggeredBy);
        if (scheduled && (task.getEnabled() == null || task.getEnabled() != 1)) {
            return List.of();
        }
        DataCollector collector = collectors.get(taskCode);
        if (collector == null) {
            throw new IllegalStateException("任务 " + taskCode + " 的采集器尚未实现");
        }

        LocalDateTime startedAt = LocalDateTime.now();
        markTask(task, "running", startedAt);

        List<DataSyncLog> logs = new ArrayList<>();
        boolean allSuccess = true;
        try {
            List<SyncOutcome> outcomes = collector.collect(scope, triggeredBy);
            for (SyncOutcome outcome : outcomes) {
                allSuccess &= outcome.success();
                logs.add(persistLog(task, outcome, triggeredBy, operatorId, startedAt));
            }
        } catch (RuntimeException ex) {
            log.error("同步任务 {} 执行失败", taskCode, ex);
            allSuccess = false;
            logs.add(persistLog(task, SyncOutcome.failed(task.getDomain(), scope == null ? "" : scope,
                    ex.getMessage()), triggeredBy, operatorId, startedAt));
        }
        markTask(task, allSuccess ? "success" : "failed", startedAt);
        return logs;
    }

    /**
     * Excel 兜底导入的统一日志登记：导入成功后调用，source_system=EXCEL，
     * 并发布 {@link SyncCompletedEvent} 让导入与自动拉取走同一套业务联动。
     */
    public DataSyncLog recordFallbackImport(String domain, String scope, int totalCount, int upsertCount,
                                            int pendingCount, String message, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        DataSyncLog entry = new DataSyncLog();
        entry.setTaskCode("excel-import");
        entry.setSourceSystem("EXCEL");
        entry.setDomain(domain);
        entry.setScope(scope == null ? "" : scope);
        entry.setStatus("success");
        entry.setTotalCount(totalCount);
        entry.setUpsertCount(upsertCount);
        entry.setPendingCount(pendingCount);
        entry.setMessage(message);
        entry.setTriggeredBy("fallback-import");
        entry.setOperatorId(operatorId);
        entry.setStartedAt(now);
        entry.setFinishedAt(now);
        syncLogMapper.insert(entry);
        eventPublisher.publishEvent(new SyncCompletedEvent(this, entry.getTaskCode(),
                domain, entry.getScope(), entry.getId(), entry.getTriggeredBy()));
        return entry;
    }

    private DataSyncLog persistLog(SyncTask task, SyncOutcome outcome, String triggeredBy,
                                   Long operatorId, LocalDateTime startedAt) {
        DataSyncLog entry = new DataSyncLog();
        entry.setTaskCode(task.getTaskCode());
        entry.setSourceSystem(task.getSourceSystem());
        entry.setDomain(outcome.domain());
        entry.setScope(outcome.scope() == null ? "" : outcome.scope());
        entry.setStatus(outcome.success() ? "success" : "failed");
        entry.setTotalCount(outcome.totalCount());
        entry.setUpsertCount(outcome.upsertCount());
        entry.setPendingCount(outcome.pendingCount());
        entry.setMessage(outcome.message());
        entry.setTriggeredBy(triggeredBy);
        entry.setOperatorId(operatorId);
        entry.setStartedAt(startedAt);
        entry.setFinishedAt(LocalDateTime.now());
        syncLogMapper.insert(entry);
        if (outcome.success()) {
            eventPublisher.publishEvent(new SyncCompletedEvent(this, task.getTaskCode(),
                    outcome.domain(), entry.getScope(), entry.getId(), triggeredBy));
        }
        return entry;
    }

    private void markTask(SyncTask task, String status, LocalDateTime runAt) {
        SyncTask update = new SyncTask();
        update.setId(task.getId());
        update.setLastStatus(status);
        update.setLastRunAt(runAt);
        taskMapper.updateById(update);
    }
}
