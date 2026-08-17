package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.bu.management.entity.EmailMessage;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.EmailMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailQueryServiceTest {
    @Test
    void anotherUsersMessageIdIsReportedAsNotFound() {
        EmailMessageMapper mapper = mock(EmailMessageMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        EmailQueryService service = new EmailQueryService(mapper, new ObjectMapper());

        assertThatThrownBy(() -> service.detail(7L, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("邮件不存在");
        verify(mapper).selectOne(any(AbstractWrapper.class));
    }
}
