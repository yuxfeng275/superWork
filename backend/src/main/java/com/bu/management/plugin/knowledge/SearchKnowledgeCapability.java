package com.bu.management.plugin.knowledge;

import com.bu.management.annotation.Capability;
import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityHandler;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.plugin.support.ToolArgs;
import com.bu.management.plugin.support.ToolResults;
import com.bu.management.service.KbHit;
import com.bu.management.service.KbSearchException;
import com.bu.management.service.KbSearchService;
import com.bu.management.service.ProjectFactAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 检索合拍知识库：把 ngram 全文检索包成能力，供助手在回答前先召回。
 *
 * <p>无命中不是错误：返回 {@code {"hits":[],"count":0}}，由模型自己去问用户；
 * 检索故障则回错误码 {@code KB_SEARCH_FAILED}，不能伪装成「知识库没有」。
 *
 * <p>范围：默认只放行 COMPANY 与本人的 OWNER 文档；模型传了 {@code projectId} 时，
 * 先校验用户对该项目有读权限，再按项目范围召回。项目 ID 是模型给的，不可信，校验在服务端。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Capability(id = "search_knowledge",
        kind = CapabilityKind.KNOWLEDGE,
        risk = CapabilityRisk.READ,
        title = "检索合拍知识库",
        whenToUse = "回答产品能力/方案/案例/口径类问题时先检索知识库",
        inputSchema = """
                {"type":"object","properties":{"query":{"type":"string","description":"检索问句，至少 2 个字，用业务原话而不是关键词堆砌"},"limit":{"type":"integer","description":"返回条数，默认 5，最大 10"},"projectId":{"type":"integer","description":"当前项目 ID，可选；传了就只在该项目与公司级范围内召回"}},"required":["query"]}
                """,
        timeoutMs = 15000)
@Component
@RequiredArgsConstructor
public class SearchKnowledgeCapability implements CapabilityHandler {

    /** 单块正文回给模型的展示上限：够判断相关性即可，不要占满上下文。 */
    private static final int SNIPPET_CHARS = 300;

    private final KbSearchService kbSearchService;

    /** 项目准入：模型给的 projectId 不可信，检索前必须服务端校验。 */
    private final ProjectFactAccessGuard accessGuard;

    private final ObjectMapper objectMapper;

    @Override
    public CapabilityResult execute(JsonNode input, CapabilityContext ctx) throws Exception {
        String query = ToolArgs.text(input, "query");
        Integer limitArg = ToolArgs.integer(input, "limit");
        int limit = limitArg == null ? KbSearchService.DEFAULT_LIMIT : limitArg;
        Integer projectIdArg = ToolArgs.integer(input, "projectId");
        Long projectId = projectIdArg == null ? null : projectIdArg.longValue();
        if (projectId != null
                && (ctx.userId() == null || !accessGuard.canRead(ctx.userId(), projectId))) {
            return CapabilityResult.fail("KB_SCOPE_DENIED", "无权检索该项目文档");
        }
        List<KbHit> hits;
        try {
            hits = kbSearchService.search(query, ctx.userId(),
                    projectId == null ? KbSearchService.Scope.none() : KbSearchService.Scope.project(projectId),
                    limit);
        } catch (KbSearchException e) {
            // 检索故障必须原样告诉模型：否则它会把「检索不了」当成「没有这条知识」
            return CapabilityResult.fail(e.code(), e.getMessage());
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode rows = root.putArray("hits");
        for (KbHit hit : hits) {
            ObjectNode row = rows.addObject();
            row.put("title", hit.documentTitle() == null ? "" : hit.documentTitle());
            row.put("heading", hit.heading() == null ? "" : hit.heading());
            row.put("snippet", snippet(hit.content()));
            row.put("score", hit.score());
        }
        root.put("count", hits.size());
        return ToolResults.json(objectMapper.writeValueAsString(root));
    }

    /** 正文截断：只留前 300 字，超出部分用省略号标记，避免模型把半句话当结论。 */
    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > SNIPPET_CHARS ? content.substring(0, SNIPPET_CHARS) + "…" : content;
    }
}
