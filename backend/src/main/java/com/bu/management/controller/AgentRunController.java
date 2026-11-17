package com.bu.management.controller;

import com.bu.management.agent.AgentOrchestrator;
import com.bu.management.agent.AgentRunResult;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务型 Agent 的灰度入口：把一句目标交给编排器（先规划、再逐步执行能力）。
 *
 * <p>只对 {@code ai.agent.gray-user-ids} 里的用户开放；未开通一律 403，且不触达模型与能力。
 * 这是灰度期唯一的调用入口——助手对话链路仍然走侧车，互不影响。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@RestController
@RequestMapping("/api/ai-agent")
@RequiredArgsConstructor
public class AgentRunController {

    /** 目标长度上限：再长的目标应当拆成多个任务，而不是塞进一次规划。 */
    private static final int GOAL_MAX_CHARS = 500;

    private final AgentOrchestrator orchestrator;

    /** 请求体：只有一句自然语言目标。 */
    public record OrchestrateRequest(String goal) {
    }

    /**
     * 执行一次任务编排。
     *
     * @param userId 当前用户：必须已开通灰度
     * @param request 目标
     * @return 编排结果（永不 500：内部异常一律转成失败结果）
     */
    @PostMapping("/orchestrate")
    public ResponseEntity<Result<AgentRunResult>> orchestrate(
            @RequestAttribute("userId") Long userId,
            @RequestBody(required = false) OrchestrateRequest request) {
        if (!orchestrator.allow(userId)) {
            return ResponseEntity.status(403)
                    .body(Result.error(403, "任务编排未开通（灰度范围外）"));
        }
        String goal = request == null ? null : request.goal();
        if (!StringUtils.hasText(goal)) {
            return ResponseEntity.badRequest().body(Result.error(400, "目标不能为空"));
        }
        String trimmed = goal.strip();
        if (trimmed.length() > GOAL_MAX_CHARS) {
            return ResponseEntity.badRequest().body(Result.error(400,
                    "目标过长（上限 " + GOAL_MAX_CHARS + " 字）"));
        }
        AgentRunResult result = orchestrator.run(trimmed,
                CapabilityContext.agent(userId, null, false));
        return ResponseEntity.ok(Result.success(result));
    }
}
