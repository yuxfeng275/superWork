package com.bu.management.plugin.artifact;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.AiArtifact;
import com.bu.management.plugin.support.ProjectFactMessages;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.AiArtifactService;
import com.bu.management.service.ProjectFactAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * save_artifact 的能力化实现：方法体与 AiAgentToolService#saveArtifact 逐字一致。
 *
 * <p>写操作：idempotent=false。无证据不入评审队列；自动送审失败不回退工具结果
 * （制品仍是草稿，白天人可手动提交）。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Capability(id = "save_artifact",
        kind = CapabilityKind.TOOL,
        risk = CapabilityRisk.WRITE,
        title = "保存制品并送审",
        whenToUse = "把夜间产出的制品（方案/补丁说明/修复说明/审查意见处理/清单）存成待评审草稿；"
                + "必须挂证据（project_fact.id 列表），无证据的制品不进评审队列",
        inputSchema = """
                {"type":"object","properties":{"projectId":{"type":"integer","description":"项目 ID，必填"},"title":{"type":"string","description":"制品标题，必填"},"kind":{"type":"string","description":"制品类型，必填：PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST"},"content":{"type":"string","description":"制品正文（方案正文/补丁说明/diff 摘要），必填"},"evidence":{"type":"string","description":"证据 JSON 数组字符串：project_fact.id 列表，如 [12,15]，必填"},"taskId":{"type":"string","description":"agent-autoloop 任务号，可选，用于回溯"}}}
                """,
        timeoutMs = 30000,
        idempotent = false)
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveArtifactCapability implements CapabilityHandler {

    private final AiArtifactService aiArtifactService;
    private final ProjectFactAccessGuard projectFactAccessGuard;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
        Long userId = ctx.userId();
        long projectId = input.path("projectId").asLong(0);
        if (projectId <= 0) {
            return ToolResults.error("缺少 projectId");
        }
        if (!projectFactAccessGuard.canWrite(userId, projectId)) {
            log.warn("AI 工具 save_artifact 拒绝：用户 {} 对项目 {} 无写权限", userId, projectId);
            return ToolResults.error(ProjectFactMessages.NO_FACT_WRITE);
        }
        String title = ToolArgs.text(input, "title");
        String kind = ToolArgs.text(input, "kind");
        String content = ToolArgs.text(input, "content");
        String evidence = ToolArgs.text(input, "evidence");
        if (!StringUtils.hasText(title)) {
            return ToolResults.error("title 不能为空");
        }
        if (!StringUtils.hasText(kind)) {
            return ToolResults.error("kind 不能为空（PLAN/PATCH/FIX_NOTE/REVIEW_FIX/CHECKLIST）");
        }
        if (!StringUtils.hasText(content)) {
            return ToolResults.error("content 不能为空");
        }
        if (!StringUtils.hasText(evidence)) {
            return ToolResults.error(AiArtifactService.EVIDENCE_REQUIRED);
        }
        try {
            AiArtifact artifact = aiArtifactService.create(userId, projectId,
                    new AiArtifactService.ArtifactCreateRequest(title, kind, content, evidence,
                            null, null, projectId, ToolArgs.text(input, "taskId")));
            String status = artifact.getStatus();
            try {
                AiArtifact submitted = aiArtifactService.submit(userId, artifact.getId());
                if (submitted != null && submitted.getStatus() != null) {
                    status = submitted.getStatus();
                }
            } catch (Exception e) {
                log.warn("制品 {} 自动送审失败，保留草稿待人工提交：{}", artifact.getId(), e.getMessage());
            }
            return ToolResults.text("制品已入评审队列（id=" + artifact.getId()
                    + "，kind=" + artifact.getKind() + "，状态 " + status + "）");
        } catch (IllegalArgumentException e) {
            return ToolResults.error(e.getMessage());
        } catch (Exception e) {
            return ToolResults.error("制品保存失败：" + e.getMessage());
        }
    }
}
