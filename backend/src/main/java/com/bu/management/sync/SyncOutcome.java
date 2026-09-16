package com.bu.management.sync;

/**
 * 单次域级同步结果（采集器返回，由编排器统一落 data_sync_log）。
 */
public record SyncOutcome(
        String domain,
        String scope,
        boolean success,
        Integer totalCount,
        Integer upsertCount,
        Integer pendingCount,
        String message) {

    public static SyncOutcome success(String domain, String scope, Integer total, Integer upsert,
                                      Integer pending, String message) {
        return new SyncOutcome(domain, scope, true, total, upsert, pending, message);
    }

    public static SyncOutcome failed(String domain, String scope, String message) {
        return new SyncOutcome(domain, scope, false, null, null, null, message);
    }
}
