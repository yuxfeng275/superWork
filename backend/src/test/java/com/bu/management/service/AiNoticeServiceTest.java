package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.AiNoticeReadMapper;
import com.bu.management.mapper.WorkLogMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AiNoticeService 聚合/已读去重单元测试（纯 Mockito，无 DB）。
 */
@ExtendWith(MockitoExtension.class)
class AiNoticeServiceTest {

    @Mock
    private AiNoticeReadMapper readMapper;
    @Mock
    private WorklogNoticeSource worklogSource;
    @Mock
    private OaNoticeSource oaSource;
    @Mock
    private MailNoticeSource mailSource;

    private AiNoticeService service;

    @BeforeEach
    void setUp() {
        service = new AiNoticeService(readMapper, worklogSource, oaSource, mailSource);
    }

    private AiNoticeService.Notice notice(String kind, boolean read) {
        return new AiNoticeService.Notice(kind, "标题", "内容", "/tasks", LocalDate.of(2026, 9, 8), read);
    }

    @Test
    @DisplayName("list：三个来源都无通知时返回空列表")
    void emptyWhenNoSources() {
        assertThat(service.list(7L)).isEmpty();
    }

    @Test
    @DisplayName("list：来源产出通知时聚合返回并查询已读状态")
    void aggregatesAndChecksRead() {
        lenient().when(worklogSource.compute(7L, LocalDate.now())).thenReturn(notice("WORKLOG_MISSING", false));
        lenient().when(readMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        List<AiNoticeService.Notice> result = service.list(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).read()).isFalse();
    }

    @Test
    @DisplayName("markRead：插入已读记录")
    void markReadInserts() {
        service.markRead(7L, "WORKLOG_MISSING", LocalDate.of(2026, 9, 8));

        verify(readMapper).insert(any(com.bu.management.entity.AiNoticeRead.class));
    }

    @Test
    @DisplayName("markRead：重复插入被幂等吞掉，不抛异常")
    void markReadDedup() {
        lenient().when(readMapper.insert(any(com.bu.management.entity.AiNoticeRead.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        service.markRead(7L, "WORKLOG_MISSING", LocalDate.of(2026, 9, 8));
        // 不抛异常即通过
    }

    @Test
    @DisplayName("unreadCount：统计未读条数（已读1条+未读1条=1）")
    void countsUnread() {
        lenient().when(worklogSource.compute(7L, LocalDate.now())).thenReturn(notice("WORKLOG_MISSING", false));
        lenient().when(oaSource.compute(7L, LocalDate.now())).thenReturn(notice("OA_PENDING", false));
        // WORKLOG_MISSING 已读（count>0），OA_PENDING 未读（count=0）
        // 第一个查询 WORKLOG_MISSING → 已读(1)；第二个 OA_PENDING → 未读(0)
        when(readMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);

        assertThat(service.unreadCount(7L)).isEqualTo(1);
    }
}
