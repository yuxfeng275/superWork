package com.bu.management.sync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.DataSyncLog;
import com.bu.management.entity.SyncTask;
import com.bu.management.mapper.DataSyncLogMapper;
import com.bu.management.mapper.SyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步任务配置查询与维护。
 */
@Service
@RequiredArgsConstructor
public class SyncTaskService {

    private final SyncTaskMapper taskMapper;
    private final DataSyncLogMapper syncLogMapper;

    public List<SyncTask> listTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<SyncTask>().orderByAsc(SyncTask::getId));
    }

    /** 更新 cron / 启用状态（均为可选，传 null 表示不修改）。 */
    public SyncTask updateTask(String taskCode, String cron, Boolean enabled) {
        SyncTask task = taskMapper.selectOne(new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTaskCode, taskCode));
        if (task == null) {
            throw new IllegalArgumentException("同步任务不存在: " + taskCode);
        }
        if (cron != null) {
            if (StringUtils.hasText(cron) && !CronExpression.isValidExpression(cron)) {
                throw new IllegalArgumentException("非法的 cron 表达式: " + cron);
            }
            task.setCron(StringUtils.hasText(cron) ? cron.trim() : null);
        }
        if (enabled != null) {
            task.setEnabled(enabled ? 1 : 0);
        }
        taskMapper.updateById(task);
        return task;
    }

    /** 各数据域最近一次成功同步摘要（前端报表页「数据截至」展示用）。 */
    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String domain : List.of("contract", "worklog", "cost", "org", "member")) {
            DataSyncLog latest = syncLogMapper.selectOne(new LambdaQueryWrapper<DataSyncLog>()
                    .eq(DataSyncLog::getDomain, domain)
                    .eq(DataSyncLog::getStatus, "success")
                    .orderByDesc(DataSyncLog::getId)
                    .last("LIMIT 1"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("domain", domain);
            item.put("lastSuccess", latest);
            result.add(item);
        }
        return result;
    }
}
