package com.bu.management.controller;

import com.bu.management.entity.OpportunityCoach;
import com.bu.management.entity.SalesOpportunityFollowUp;
import com.bu.management.integration.DigestLlmClient;
import com.bu.management.service.OpportunityCoachService;
import com.bu.management.service.OpportunityFollowUpService;
import com.bu.management.vo.Result;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商机 AI 教练的站内接口：看最近建议、手动重新生成、采纳一条下一步。
 *
 * <p>路径挂在 {@code /api/opportunities/{id}/coach} 下，与商机跟进闭环同一前缀，前端一套权限就好。
 * 这里注入 {@link OpportunityFollowUpService} <b>只为</b> {@code get(id)} 校验商机存在
 * （Controller 层没有环：Service 之间的依赖是单向的 FollowUp → Coach）。
 */
@RestController
@RequestMapping("/api/opportunities")
@RequiredArgsConstructor
public class OpportunityCoachController {

    /** 无可用摘要模型时的提示（与周报生成保持同一句话，用户不用记两套）。 */
    private static final String NO_MODEL_MESSAGE =
            "未配置可用的摘要模型：请在「模型管理」勾选「摘要使用」的模型";

    private final OpportunityCoachService coachService;
    private final OpportunityFollowUpService followUpService;
    private final DigestLlmClient digestLlmClient;

    /** 采纳请求体：{@code nextActions} 的下标。 */
    public record AdoptRequest(int actionIndex) {
    }

    /** 某商机最近的教练建议（最多 5 条，新的在前；content 已解析成结构化对象）。 */
    @GetMapping("/{id}/coach")
    public Result<List<Map<String, Object>>> list(@PathVariable Long id,
                                                  @RequestAttribute("userId") Long userId) {
        followUpService.get(id);
        return Result.success(coachService.list(id).stream().map(this::toItem).toList());
    }

    /**
     * 手动重新生成一条教练建议。
     *
     * <p>先判有没有可用模型：没配就直接 400 说清楚，不要先给前端一个「生成中」再默默失败。
     * 已有 PENDING/GENERATING 行时返回 409（「正在生成中，请稍后」），避免连点烧额度。
     */
    @PostMapping("/{id}/coach")
    public Result<Map<String, Object>> regenerate(@PathVariable Long id,
                                                  @RequestAttribute("userId") Long userId) {
        followUpService.get(id);
        if (digestLlmClient.candidateCount() == 0) {
            return Result.error(400, NO_MODEL_MESSAGE);
        }
        try {
            OpportunityCoach coach = coachService.regenerate(id, userId);
            return Result.success(toItem(coach));
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    /** 采纳第 {@code actionIndex} 条下一步：写一条 AI_COACH 来源的跟进记录并刷新商机快照。 */
    @PostMapping("/{id}/coach/{coachId}/adopt")
    public Result<SalesOpportunityFollowUp> adopt(@PathVariable Long id,
                                                  @PathVariable Long coachId,
                                                  @RequestBody AdoptRequest request,
                                                  @RequestAttribute("userId") Long userId) {
        followUpService.get(id);
        if (request == null) {
            throw new IllegalArgumentException("缺少 actionIndex");
        }
        return Result.success(coachService.adopt(coachId, request.actionIndex(), userId));
    }

    /** 列表一条：只暴露前端要渲染的字段，content 走 Coach 的结构化解析（解析失败给空对象）。 */
    private Map<String, Object> toItem(OpportunityCoach coach) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", coach.getId());
        item.put("opportunityId", coach.getOpportunityId());
        item.put("promptId", coach.getPromptId());
        item.put("status", coach.getStatus());
        item.put("triggerSource", coach.getTriggerSource());
        item.put("createdAt", coach.getCreatedAt());
        item.put("generatedAt", coach.getGeneratedAt());
        item.put("provider", coach.getProvider());
        item.put("model", coach.getModel());
        item.put("error", coach.getError());
        item.put("content", coachService.view(coach));
        return item;
    }
}
