package com.bu.management.service;

import com.bu.management.entity.WorktimeSyncLog;
import com.bu.management.mapper.WorktimeSyncLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工时同步健康通知：失败/超时提醒，权限与降级。
 */
@ExtendWith(MockitoExtension.class)
class WorktimeSyncNoticeSourceTest {

    @Mock
    private WorktimeSyncLogMapper syncLogMapper;
    @Mock
    private SysRoleService sysRoleService;

    private WorktimeSyncLog log(String status, LocalDateTime startedAt) {
        WorktimeSyncLog log = new WorktimeSyncLog();
        log.setSyncType("contract");
        log.setStatus(status);
        log.setStartedAt(startedAt);
        log.setMessage("msg");
        return log;
    }

    @Test
    @DisplayName("无 kpi:manage 权限不生成通知")
    void skipsWithoutPermission() {
        var source = new WorktimeSyncNoticeSource(syncLogMapper, sysRoleService);
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("email:view"));

        assertThat(source.compute(7L, java.time.LocalDate.now())).isNull();
    }

    @Test
    @DisplayName("最近同步失败时提醒管理者")
    void alertsOnFailure() {
        var source = new WorktimeSyncNoticeSource(syncLogMapper, sysRoleService);
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("kpi:manage"));
        when(syncLogMapper.selectOne(any())).thenReturn(log("failed", LocalDateTime.now().minusHours(1)));

        var notice = source.compute(7L, java.time.LocalDate.now());

        assertThat(notice).isNotNull();
        assertThat(notice.kind()).isEqualTo("WORKTIME_SYNC_FAILED");
        assertThat(notice.link()).isEqualTo("/kpi-report");
    }

    @Test
    @DisplayName("超过 26 小时无成功同步时提醒")
    void alertsWhenStale() {
        var source = new WorktimeSyncNoticeSource(syncLogMapper, sysRoleService);
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("kpi:manage"));
        when(syncLogMapper.selectOne(any()))
                .thenReturn(log("success", LocalDateTime.now().minusHours(30)));

        var notice = source.compute(7L, java.time.LocalDate.now());

        assertThat(notice).isNotNull();
        assertThat(notice.body()).contains("26 小时");
    }

    @Test
    @DisplayName("最近同步成功且新鲜时不提醒")
    void silentWhenHealthy() {
        var source = new WorktimeSyncNoticeSource(syncLogMapper, sysRoleService);
        when(sysRoleService.getPermissionCodesByUserId(7L)).thenReturn(List.of("kpi:manage"));
        when(syncLogMapper.selectOne(any()))
                .thenReturn(log("success", LocalDateTime.now().minusHours(12)));

        assertThat(source.compute(7L, java.time.LocalDate.now())).isNull();
    }
}
