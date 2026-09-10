package com.bu.management.service;

import com.bu.management.entity.EmailAction;
import com.bu.management.entity.EmailMessage;
import com.bu.management.mapper.EmailActionMapper;
import com.bu.management.mapper.EmailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮件行动闭环登记：幂等、归属校验、闭环回写。
 */
@ExtendWith(MockitoExtension.class)
class EmailActionLinkServiceTest {

    @Mock
    private EmailActionMapper actionMapper;
    @Mock
    private EmailMessageMapper messageMapper;

    private EmailActionLinkService service;

    @BeforeEach
    void setUp() {
        service = new EmailActionLinkService(actionMapper, messageMapper);
    }

    private EmailMessage message(Long id, Long owner) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setOwnerUserId(owner);
        message.setSubject("合同确认");
        return message;
    }

    @Test
    @DisplayName("register：同条目重复转化返回既有记录，不重复建")
    void registerIsIdempotent() {
        EmailAction existing = new EmailAction();
        existing.setId(5L);
        existing.setOwnerUserId(7L);
        existing.setMessageId(11L);
        existing.setItemKind("TODO");
        existing.setItemTitle("回复合同");
        existing.setActionType("TASK");
        existing.setTargetId(88L);
        existing.setStatus("OPEN");
        when(actionMapper.selectOne(any())).thenReturn(existing);

        EmailAction result = service.register(7L, 11L, "TODO", "回复合同", "TASK", 88L, "回复合同");

        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getTargetId()).isEqualTo(88L);
        verify(actionMapper, never()).insert(any(EmailAction.class));
    }

    @Test
    @DisplayName("register：邮件不属于当前用户时拒绝")
    void registerRejectsForeignMessage() {
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.register(7L, 11L, "TODO", "回复合同", "TASK", 88L, "回复合同"))
                .isInstanceOf(com.bu.management.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("register：归属校验通过后落库 OPEN 记录")
    void registerPersistsOpenAction() {
        when(actionMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectOne(any())).thenReturn(message(11L, 7L));

        service.register(7L, 11L, "RISK", "交付延期", "ISSUE", 3L, "交付延期");

        ArgumentCaptor<EmailAction> captor = ArgumentCaptor.forClass(EmailAction.class);
        verify(actionMapper, times(1)).insert(captor.capture());
        EmailAction saved = captor.getValue();
        assertThat(saved.getOwnerUserId()).isEqualTo(7L);
        assertThat(saved.getItemKind()).isEqualTo("RISK");
        assertThat(saved.getActionType()).isEqualTo("ISSUE");
        assertThat(saved.getTargetId()).isEqualTo(3L);
        assertThat(saved.getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("markClosed：只回写对应目标的 OPEN 记录")
    void markClosedUpdatesOpenRecordsOnly() {
        EmailAction open = new EmailAction();
        open.setId(1L);
        open.setActionType("TASK");
        open.setTargetId(88L);
        open.setStatus("OPEN");
        when(actionMapper.selectList(any())).thenReturn(List.of(open));

        service.markClosed("TASK", 88L);

        ArgumentCaptor<EmailAction> captor = ArgumentCaptor.forClass(EmailAction.class);
        verify(actionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(captor.getValue().getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("markClosed：回写失败不影响业务方（吞异常）")
    void markClosedNeverThrows() {
        when(actionMapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        service.markClosed("TASK", 88L);
        verify(actionMapper, never()).updateById(any(EmailAction.class));
    }
}
