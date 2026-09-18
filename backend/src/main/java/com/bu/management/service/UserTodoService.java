package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.User;
import com.bu.management.entity.UserTodo;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.UserTodoMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserTodoService {

    public static final String SOURCE_KEY_MATTER_WEEKLY = "KEY_MATTER_WEEKLY";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_DONE = "DONE";

    private final UserTodoMapper todoMapper;
    private final UserMapper userMapper;

    @Transactional
    public List<UserTodo> syncMentions(
            String sourceType,
            Long sourceId,
            Long actorId,
            String actorName,
            String sourceTitle,
            String link,
            LocalDate sourceDate,
            List<String> texts) {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, 1));
        List<MentionParser.Mention> mentions = MentionParser.resolveAll(
                users, texts == null ? new String[0] : texts.toArray(String[]::new));
        List<UserTodo> existing = todoMapper.selectList(new LambdaQueryWrapper<UserTodo>()
                .eq(UserTodo::getSourceType, sourceType)
                .eq(UserTodo::getSourceId, sourceId));
        Set<Long> alreadyAssigned = new LinkedHashSet<>();
        for (UserTodo todo : existing) {
            alreadyAssigned.add(todo.getAssigneeId());
        }
        List<UserTodo> created = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (MentionParser.Mention mention : mentions) {
            if (Objects.equals(mention.userId(), actorId) || alreadyAssigned.contains(mention.userId())) {
                continue;
            }
            UserTodo todo = new UserTodo();
            todo.setAssigneeId(mention.userId());
            todo.setActorId(actorId);
            todo.setSourceType(sourceType);
            todo.setSourceId(sourceId);
            todo.setMentionToken(mention.displayName());
            todo.setTitle(buildTitle(actorName, sourceTitle));
            todo.setExcerpt(firstNonBlank(texts));
            todo.setLink(link);
            todo.setSourceDate(sourceDate);
            todo.setStatus(STATUS_OPEN);
            todo.setCreatedAt(now);
            todoMapper.insert(todo);
            alreadyAssigned.add(mention.userId());
            created.add(todo);
        }
        return created;
    }

    public List<UserTodo> listMine(Long userId, String status) {
        LambdaQueryWrapper<UserTodo> query = new LambdaQueryWrapper<UserTodo>()
                .eq(UserTodo::getAssigneeId, userId)
                .orderByDesc(UserTodo::getCreatedAt);
        if (StringUtils.hasText(status)) {
            query.eq(UserTodo::getStatus, status);
        }
        return todoMapper.selectList(query);
    }

    public long openCount(Long userId) {
        Long count = todoMapper.selectCount(new LambdaQueryWrapper<UserTodo>()
                .eq(UserTodo::getAssigneeId, userId)
                .eq(UserTodo::getStatus, STATUS_OPEN));
        return count == null ? 0 : count;
    }

    @Transactional
    public UserTodo complete(Long id, Long userId) {
        UserTodo todo = todoMapper.selectById(id);
        if (todo == null || !Objects.equals(todo.getAssigneeId(), userId)) {
            throw new ResourceNotFoundException("待办不存在");
        }
        if (!STATUS_DONE.equals(todo.getStatus())) {
            todo.setStatus(STATUS_DONE);
            todo.setCompletedAt(LocalDateTime.now());
            todoMapper.updateById(todo);
        }
        return todo;
    }

    private String buildTitle(String actorName, String sourceTitle) {
        String actor = StringUtils.hasText(actorName) ? actorName : "同事";
        String title = StringUtils.hasText(sourceTitle) ? sourceTitle : "周进展";
        return actor + " 在「" + title + "」中提到了你";
    }

    private String firstNonBlank(List<String> texts) {
        if (texts == null) {
            return null;
        }
        for (String text : texts) {
            if (StringUtils.hasText(text)) {
                String trimmed = text.trim();
                return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
            }
        }
        return null;
    }
}
