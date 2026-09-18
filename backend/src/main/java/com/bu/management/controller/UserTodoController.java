package com.bu.management.controller;

import com.bu.management.entity.UserTodo;
import com.bu.management.service.UserTodoService;
import com.bu.management.vo.Result;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/todos")
@RequiredArgsConstructor
public class UserTodoController {

    private final UserTodoService userTodoService;

    @GetMapping
    public Result<List<UserTodo>> list(
            @RequestAttribute("userId") Long userId,
            @RequestParam(required = false) Long userIdFilter,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String status) {
        Long assigneeId = userIdFilter;
        if (assigneeId == null && user != null && !user.isBlank()) {
            var matched = userTodoService.resolveMentionUser(user);
            if (matched == null) {
                return Result.success(List.of());
            }
            assigneeId = matched.getId();
        }
        if (assigneeId == null) {
            assigneeId = userId;
        }
        return Result.success(userTodoService.listForAssignee(assigneeId, status));
    }

    @GetMapping("/open-count")
    public Result<Map<String, Long>> openCount(@RequestAttribute("userId") Long userId) {
        return Result.success(Map.of("count", userTodoService.openCount(userId)));
    }

    @PostMapping("/{id}/complete")
    public Result<UserTodo> complete(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        return Result.success(userTodoService.complete(id, userId));
    }
}
