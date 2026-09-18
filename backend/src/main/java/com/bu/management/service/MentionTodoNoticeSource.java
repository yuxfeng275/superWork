package com.bu.management.service;

import com.bu.management.entity.UserTodo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 将未完成的 @ 待办聚合进消息铃铛，每条待办一条通知。
 */
@Component
@RequiredArgsConstructor
public class MentionTodoNoticeSource {

    public static final String KIND_PREFIX = "TODO_MENTION_";

    private final UserTodoService userTodoService;

    public List<AiNoticeService.Notice> compute(Long userId, LocalDate today) {
        try {
            List<UserTodo> open = userTodoService.listMine(userId, UserTodoService.STATUS_OPEN);
            List<AiNoticeService.Notice> notices = new ArrayList<>();
            for (UserTodo todo : open) {
                LocalDate date = todo.getSourceDate() != null ? todo.getSourceDate() : today;
                notices.add(new AiNoticeService.Notice(
                        KIND_PREFIX + todo.getId(),
                        todo.getTitle(),
                        todo.getExcerpt(),
                        todo.getLink() == null || todo.getLink().isBlank() ? "/todos" : todo.getLink(),
                        date,
                        false));
            }
            return notices;
        } catch (Exception e) {
            return List.of();
        }
    }
}
