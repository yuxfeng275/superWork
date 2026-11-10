package com.bu.management.controller;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.KbDocument;
import com.bu.management.entity.SysRole;
import com.bu.management.mapper.KbDocumentMapper;
import com.bu.management.service.KbHit;
import com.bu.management.service.KbIngestService;
import com.bu.management.service.KbSearchService;
import com.bu.management.service.SysRoleService;
import com.bu.management.vo.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库站内接口：入库（仅管理岗位）与检索（登录即可）。
 *
 * <p>入库是改数据面的写操作，不对普通账号开放；检索只放行 COMPANY 与本人的 OWNER 文档，
 * 项目 / 业务线文档需要显式 scopeId，本接口暂不提供（有意保守）。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KbIngestService kbIngestService;

    private final KbSearchService kbSearchService;

    private final SysRoleService sysRoleService;

    private final KbDocumentMapper kbDocumentMapper;

    /**
     * 入库请求体。
     *
     * @param title        标题，必填
     * @param content      正文，必填
     * @param sourceType   UPLOAD / YUQUE / CASE / MANUAL，缺省 MANUAL
     * @param sourceRef    来源内唯一标识，可空
     * @param scopeType    COMPANY / BUSINESS_LINE / PROJECT / OWNER，缺省 COMPANY
     * @param scopeId      范围对象 ID，OWNER 时为本人用户 ID
     * @param businessLine 业务线名称，可空
     * @param tags         逗号分隔标签，可空
     */
    public record IngestRequest(String title, String content, String sourceType, String sourceRef,
                                String scopeType, Long scopeId, String businessLine, String tags) {
    }

    /**
     * 入库一篇文档（同来源同内容重复提交不会重建切片）。
     *
     * <p>仅管理岗位可调（{@link PositionRoles#isAdminRole}）：这是知识库的唯一写入口。
     */
    @PostMapping("/documents")
    public Result<KbDocument> ingest(@RequestAttribute("userId") Long userId,
                                     @RequestBody IngestRequest request) {
        requireAdmin(userId);
        if (request == null) {
            throw new RuntimeException("入库内容不能为空");
        }
        return Result.success(kbIngestService.ingest(
                request.title(), request.content(), request.sourceType(), request.sourceRef(),
                request.scopeType(), request.scopeId(), request.businessLine(), request.tags(), userId));
    }

    /**
     * 检索知识库。
     *
     * @param q     问句，至少 2 个字（ngram_token_size=2，更短的切不出 token）
     * @param limit 条数，默认 5，最大 10
     */
    /**
     * 知识库文档列表（最近更新的在前，最多 100 条）。
     *
     * <p>管理页用它看“知识库里到底有什么”，避免入库后没人知道写了什么。
     */
    @GetMapping("/documents")
    public Result<List<KbDocument>> listDocuments(@RequestAttribute("userId") Long userId,
                                                  @RequestParam(required = false) Integer limit) {
        requireAdmin(userId);
        int size = limit == null ? 50 : Math.max(1, Math.min(100, limit));
        return Result.success(kbDocumentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KbDocument>()
                        .orderByDesc(KbDocument::getUpdatedAt)
                        .last("limit " + size)));
    }

    /**
     * 启用 / 停用一篇文档：停用后不进检索，但切片保留，随时可再开。
     */
    @PostMapping("/documents/{id}/enabled")
    public Result<Void> setEnabled(@RequestAttribute("userId") Long userId,
                                   @org.springframework.web.bind.annotation.PathVariable Long id,
                                   @RequestParam(defaultValue = "true") boolean value) {
        requireAdmin(userId);
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) {
            throw new RuntimeException("知识库文档不存在");
        }
        doc.setEnabled(value ? 1 : 0);
        doc.setUpdatedAt(java.time.LocalDateTime.now());
        kbDocumentMapper.updateById(doc);
        return Result.success();
    }

    @GetMapping("/search")
    public Result<List<KbHit>> search(@RequestAttribute("userId") Long userId,
                                      @RequestParam(value = "q", required = false) String q,
                                      @RequestParam(value = "limit", required = false, defaultValue = "5") int limit) {
        return Result.success(kbSearchService.search(q, userId, null, limit));
    }

    /** 管理岗位校验：与 OpportunityFollowUpController#requireAdmin 同一口径。 */
    private void requireAdmin(Long userId) {
        List<SysRole> roles = sysRoleService.getRolesByUserId(userId);
        boolean admin = roles != null && roles.stream()
                .map(SysRole::getCode)
                .anyMatch(PositionRoles::isAdminRole);
        if (!admin) {
            throw new RuntimeException("仅管理岗位可维护知识库文档");
        }
    }
}
