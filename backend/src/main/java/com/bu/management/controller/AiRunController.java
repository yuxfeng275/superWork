package com.bu.management.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.AiRun;
import com.bu.management.entity.AiInvocation;
import com.bu.management.entity.AiRunNode;
import com.bu.management.mapper.AiInvocationMapper;
import com.bu.management.mapper.AiRunMapper;
import com.bu.management.orchestration.NightBatchService;
import com.bu.management.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行面后端：触发夜间批、看 run 列表与节点。
 *
 * <p>两个入口刻意分开：
 * <ul>
 *   <li>{@code POST /api/ai-runs}：登录态 + 管理岗位，人手动点「今晚跑一次」；</li>
 *   <li>{@code POST /internal/project-context/night-batch}：X-Sidecar-Token，
 *       给 agent-autoloop 的定时规则用——触发器只发「开始」，不描述流程。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-21
 */
@RestController
@RequiredArgsConstructor
public class AiRunController {

    private final NightBatchService nightBatchService;

    private final AiRunMapper runMapper;

    private final AiInvocationMapper invocationMapper;

    private final ProjectContextProperties properties;

    /** 手动触发请求体。 */
    public record TriggerRequest(Long userId, Long projectId,
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bizDate) {
    }

    /** 运行列表（最近 50 条，可按项目过滤）。 */
    @GetMapping("/api/ai-runs")
    public Result<List<AiRun>> list(@RequestAttribute("userId") Long userId,
                                    @RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) String status) {
        var query = Wrappers.<AiRun>lambdaQuery().orderByDesc(AiRun::getCreatedAt).last("limit 50");
        if (projectId != null) {
            query.eq(AiRun::getProjectId, projectId);
        }
        if (StringUtils.hasText(status)) {
            query.eq(AiRun::getStatus, status);
        }
        return Result.success(runMapper.selectList(query));
    }

    /** run 详情：run + 节点。 */
    @GetMapping("/api/ai-runs/{id}")
    public Result<RunDetail> detail(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        AiRun run = runMapper.selectById(id);
        if (run == null) {
            return Result.error(404, "run 不存在");
        }
        // 结果未知的调用数：这是「可能已经写了」的唯一显式提示，必须出现在运行面上
        Long unknown = invocationMapper.selectCount(Wrappers.<AiInvocation>lambdaQuery()
                .eq(AiInvocation::getRunId, id)
                .in(AiInvocation::getStatus, "STARTED", "UNKNOWN"));
        return Result.success(new RunDetail(run, nightBatchService.nodes(id),
                unknown == null ? 0 : unknown));
    }

    /** 手动触发：以成员身份跑一次夜间批（幂等）。 */
    @PostMapping("/api/ai-runs")
    public Result<AiRun> trigger(@RequestBody TriggerRequest body) {
        if (body == null || body.userId() == null) {
            return Result.error(400, "userId 不能为空");
        }
        try {
            return Result.success(nightBatchService.run(body.userId(), body.projectId(),
                    body.bizDate(), "API"));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(409, e.getMessage());
        }
    }

    /**
     * 触发器入口：只接收「今晚跑一次 + 目标项目 + 身份」，流程由合拍决定。
     *
     * <p>这就是「autoloop 不再决定流程」的落点：规则里写的是这个 POST，而不是一串步骤。
     */
    @PostMapping("/internal/project-context/night-batch")
    public ResponseEntity<?> triggerByToken(@RequestBody TriggerRequest body,
                                            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        if (body == null || body.userId() == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "userId 不能为空"));
        }
        try {
            AiRun run = nightBatchService.run(body.userId(), body.projectId(), body.bizDate(),
                    "AUTOLOOP");
            return ResponseEntity.ok(Result.success(run));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Result.error(409, e.getMessage()));
        }
    }

    /** run 详情响应。 */
    public record RunDetail(AiRun run, List<AiRunNode> nodes, long unknownInvocations) {
    }

    private boolean tokenOk(HttpServletRequest request) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String actual = request.getHeader("X-Sidecar-Token");
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
