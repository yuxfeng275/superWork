package com.bu.management.controller;

import com.bu.management.service.ConfirmationService;
import com.bu.management.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「待我确认」入口：一个队列、两种动作（通过 / 打回），支持批量。
 *
 * <p>身份只取登录态；每一条的权限在服务层按项目写权限再判一次，
 * 前端传来的 id 不被信任。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@RestController
@RequestMapping("/api/ai-confirmations")
@RequiredArgsConstructor
public class ConfirmationController {

    private final ConfirmationService confirmationService;

    /** 队列响应：闸门 + 制品 + 低风险条数（决定「一键通过」是否可用）。 */
    public record QueueResponse(ConfirmationService.Queue queue, int lowRiskCount) {
    }

    @GetMapping
    public Result<QueueResponse> queue(@RequestAttribute("userId") Long userId) {
        ConfirmationService.Queue queue = confirmationService.queue(userId);
        return Result.success(new QueueResponse(queue, confirmationService.lowRiskCount(queue)));
    }

    @PostMapping("/batch")
    public Result<ConfirmationService.BatchResult> batch(
            @RequestAttribute("userId") Long userId,
            @RequestBody ConfirmationService.BatchRequest request) {
        try {
            return Result.success(confirmationService.batch(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
