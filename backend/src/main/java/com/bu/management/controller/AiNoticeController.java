package com.bu.management.controller;

import com.bu.management.service.AiNoticeService;
import com.bu.management.vo.Result;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知：实时聚合工时缺填/OA待办/邮箱未绑定，已读状态幂等落库。
 */
@RestController
@RequestMapping("/api/ai/notices")
@RequiredArgsConstructor
public class AiNoticeController {

    private final AiNoticeService noticeService;

    @GetMapping
    public Result<List<AiNoticeService.Notice>> list(@RequestAttribute("userId") Long userId) {
        return Result.success(noticeService.list(userId));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Integer>> unreadCount(@RequestAttribute("userId") Long userId) {
        return Result.success(Map.of("count", noticeService.unreadCount(userId)));
    }

    @PostMapping("/read")
    public Result<Void> markRead(@RequestAttribute("userId") Long userId,
            @RequestBody Map<String, String> body) {
        String kind = body.get("kind");
        LocalDate date = LocalDate.parse(body.getOrDefault("date", LocalDate.now().toString()));
        noticeService.markRead(userId, kind, date);
        return Result.success();
    }
}
