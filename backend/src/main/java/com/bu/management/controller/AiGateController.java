package com.bu.management.controller;

import com.bu.management.entity.AiGate;
import com.bu.management.mapper.AiGateMapper;
import com.bu.management.orchestration.AiGateService;
import com.bu.management.orchestration.NightBatchService;
import com.bu.management.vo.Result;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工闸门入口：待我审批的列表 + 放行 / 打回 / 重跑。
 *
 * <p>身份只从登录态取（{@code userId}），请求体里的 userId 一律忽略；
 * 乐观校验的期望值由前端原样回传闸门快照，服务层比对不一致即拒绝——
 * 这样「打开旧页面再点一次」不会造成第二次生效。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@RestController
@RequestMapping("/api/ai-gates")
@RequiredArgsConstructor
public class AiGateController {

    private final AiGateService gateService;

    private final AiGateMapper gateMapper;

    private final NightBatchService nightBatchService;

    /** 决策请求体：expectedAttempt/expectedRework 必须与页面拿到的闸门快照一致。 */
    public record DecisionRequest(String reason, String rejectTo,
                                  Integer expectedAttempt, Integer expectedRework) {
    }

    /** 待我处理的闸门（最多 50 条）。 */
    @GetMapping("/pending")
    public Result<List<AiGate>> pending(@RequestAttribute("userId") Long userId) {
        return Result.success(gateMapper.selectList(Wrappers.<AiGate>lambdaQuery()
                .eq(AiGate::getAssigneeUserId, userId)
                .eq(AiGate::getStatus, AiGateService.STATUS_PENDING)
                .orderByAsc(AiGate::getCreatedAt)
                .last("limit 50")));
    }

    @PostMapping("/{id}/approve")
    public Result<AiGate> approve(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long id,
                                  @RequestBody(required = false) DecisionRequest body) {
        return decide(() -> {
            AiGate gate = gateService.approve(id, userId,
                    body == null ? null : body.reason(), attemptOf(body), reworkOf(body));
            resumeRunIfWaiting(gate);
            return gate;
        });
    }

    /**
     * 通过后把停在闸门上的夜间批继续跑完。
     *
     * <p>续跑失败不回滚审批：审批已经生效，run 会留在 WAITING_HUMAN，
     * 运行面上能直接看到并手动补跑——比悄悄把审批撤销掉更可解释。
     */
    private void resumeRunIfWaiting(AiGate gate) {
        try {
            nightBatchService.resumeAfterGate(gate.getRunId());
        } catch (RuntimeException e) {
            log.warn("闸门通过后续跑失败 runId={}：{}", gate.getRunId(), e.getMessage());
        }
    }

    @PostMapping("/{id}/reject")
    public Result<AiGate> reject(@RequestAttribute("userId") Long userId,
                                 @PathVariable Long id,
                                 @RequestBody DecisionRequest body) {
        return decide(() -> gateService.reject(id, userId, body.reason(), body.rejectTo(),
                attemptOf(body), reworkOf(body)));
    }

    @PostMapping("/{id}/retry")
    public Result<AiGate> retry(@RequestAttribute("userId") Long userId,
                                @PathVariable Long id,
                                @RequestBody(required = false) DecisionRequest body) {
        return decide(() -> gateService.retry(id, userId, attemptOf(body), reworkOf(body)));
    }

    private Result<AiGate> decide(java.util.function.Supplier<AiGate> action) {
        try {
            return Result.success(action.get());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            // 已处理 / 已过期 / 不是指派给你：都按冲突返回，前端提示刷新
            return Result.error(409, e.getMessage());
        }
    }

    private static int attemptOf(DecisionRequest body) {
        if (body == null || body.expectedAttempt() == null) {
            throw new IllegalArgumentException("缺少 expectedAttempt（请刷新后重试）");
        }
        return body.expectedAttempt();
    }

    private static int reworkOf(DecisionRequest body) {
        if (body == null || body.expectedRework() == null) {
            throw new IllegalArgumentException("缺少 expectedRework（请刷新后重试）");
        }
        return body.expectedRework();
    }
}
