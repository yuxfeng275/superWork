package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.User;
import com.bu.management.entity.UserTodo;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.UserTodoMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserTodoServiceTest {

    @Mock private UserTodoMapper todoMapper;
    @Mock private UserMapper userMapper;

    private UserTodoService service;

    @BeforeEach
    void setUp() {
        service = new UserTodoService(todoMapper, userMapper);
    }

    @Test
    void syncMentionsCreatesTodosForNewAssignees() {
        User mentioned = user(8L, "lisi", "李四");
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mentioned));
        when(todoMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.syncMentions(
                "KEY_MATTER_WEEKLY",
                21L,
                16L,
                "姜涛",
                "皇家项目周进展",
                "/key-matters?matterId=11",
                LocalDate.of(2026, 9, 14),
                List.of("请 @李四 确认上线窗口"));

        ArgumentCaptor<UserTodo> captor = ArgumentCaptor.forClass(UserTodo.class);
        verify(todoMapper).insert(captor.capture());
        UserTodo saved = captor.getValue();
        assertThat(saved.getAssigneeId()).isEqualTo(8L);
        assertThat(saved.getSourceType()).isEqualTo("KEY_MATTER_WEEKLY");
        assertThat(saved.getSourceId()).isEqualTo(21L);
        assertThat(saved.getMentionToken()).isEqualTo("李四");
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getTitle()).contains("姜涛");
        assertThat(saved.getLink()).isEqualTo("/key-matters?matterId=11");
    }

    @Test
    void syncMentionsIsIdempotentForSameAssigneeAndSource() {
        User mentioned = user(8L, "lisi", "李四");
        UserTodo existing = new UserTodo();
        existing.setId(3L);
        existing.setAssigneeId(8L);
        existing.setMentionToken("李四");
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mentioned));
        when(todoMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        service.syncMentions(
                "KEY_MATTER_WEEKLY",
                21L,
                16L,
                "姜涛",
                "皇家项目周进展",
                "/key-matters?matterId=11",
                LocalDate.of(2026, 9, 14),
                List.of("请 @李四 确认上线窗口"));

        verify(todoMapper, never()).insert(any(UserTodo.class));
    }

    @Test
    void completeMarksTodoDoneForOwner() {
        UserTodo todo = new UserTodo();
        todo.setId(9L);
        todo.setAssigneeId(8L);
        todo.setStatus("OPEN");
        when(todoMapper.selectById(9L)).thenReturn(todo);

        service.complete(9L, 8L);

        assertThat(todo.getStatus()).isEqualTo("DONE");
        assertThat(todo.getCompletedAt()).isNotNull();
        verify(todoMapper).updateById(todo);
    }

    private User user(Long id, String username, String realName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        user.setStatus(1);
        return user;
    }
}
