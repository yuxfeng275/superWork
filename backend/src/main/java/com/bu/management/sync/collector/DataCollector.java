package com.bu.management.sync.collector;

import com.bu.management.sync.SyncOutcome;

import java.util.List;

/**
 * 数据采集器：只负责「取数 + 交给统一管道」，upsert/归属判定/映射保护复用现有 service。
 * 每个采集器对应 sync_task 表中的一条任务。
 */
public interface DataCollector {

    /** 对应 sync_task.task_code */
    String taskCode();

    /**
     * 执行采集。
     *
     * @param scope       同步范围（语义由各采集器定义：合同=年份，月度=YYYY-MM 或空）
     * @param triggeredBy schedule / manual / fallback-import
     * @return 域级结果列表（一个任务可能覆盖多个域，如工时月度=worklog+cost）
     */
    List<SyncOutcome> collect(String scope, String triggeredBy);
}
