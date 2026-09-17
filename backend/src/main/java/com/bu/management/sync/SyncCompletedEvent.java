package com.bu.management.sync;

import org.springframework.context.ApplicationEvent;

/**
 * 域级同步成功事件：驱动下游业务联动（归属重扫、快照失效等，P3 接入监听器）。
 */
public class SyncCompletedEvent extends ApplicationEvent {

    private final String taskCode;
    private final String domain;
    private final String scope;
    private final Long syncLogId;
    private final String triggeredBy;

    public SyncCompletedEvent(Object source, String taskCode, String domain, String scope,
                              Long syncLogId, String triggeredBy) {
        super(source);
        this.taskCode = taskCode;
        this.domain = domain;
        this.scope = scope;
        this.syncLogId = syncLogId;
        this.triggeredBy = triggeredBy;
    }

    public String getTaskCode() {
        return taskCode;
    }

    public String getDomain() {
        return domain;
    }

    public String getScope() {
        return scope;
    }

    public Long getSyncLogId() {
        return syncLogId;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }
}
