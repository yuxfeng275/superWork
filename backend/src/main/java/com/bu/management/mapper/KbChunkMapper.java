package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.KbChunk;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库切片读写入口 + 全文检索。
 *
 * <p>检索是 MySQL 8 的 ngram 全文索引：{@code MATCH ... AGAINST} 必须和建索引时
 * 的列顺序一致（heading, content），两处都能命中；{@code > 0} 过滤掉零相关度的行，
 * 否则 LIMIT 会被「只是共享了一个 2-gram」的噪声占满。
 *
 * <p>可见范围写死在 SQL 里，不靠调用方传参控制：
 * COMPANY 全员可见；OWNER 只有本人；PROJECT / BUSINESS_LINE 必须显式传各自的 ID
 * 才放行——这是有意为之的保守默认，宁可少召回，也不把别人的项目文档捞出来。
 * 项目与业务线分两个参数传，不能共用一个 scopeId：两者 ID 空间独立，共用一个参数时
 * 项目 12 的成员会命中业务线 12 的文档，属于跨范围泄漏。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 全文检索切片（自然语言模式）。
     *
     * <p>返回 Map 而不是 POJO：这里只做「取行」，列名规整与类型转换集中在
     * {@code KbSearchService} 一处，SQL 调整不用同步改实体。
     *
     * @param q              用户问句原文（长度校验由调用方做，本方法只负责查）
     * @param userId         调用人，用于放行 OWNER 范围
     * @param projectId      项目 ID；为 null 时 PROJECT 文档一律不召回
     * @param businessLineId 业务线 ID；为 null 时 BUSINESS_LINE 文档一律不召回
     * @param limit          返回条数（调用方已夹到 1..10）
     */
    @Select("SELECT c.id, c.document_id AS documentId, c.heading, c.content, d.title AS documentTitle, "
            + "d.scope_type AS scopeType, d.scope_id AS scopeId, "
            + "MATCH(c.heading, c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) AS score "
            + "FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id "
            + "WHERE d.enabled=1 AND MATCH(c.heading, c.content) AGAINST(#{q} IN NATURAL LANGUAGE MODE) > 0 "
            + "AND (d.scope_type='COMPANY' OR (d.scope_type='OWNER' AND d.scope_id=#{userId}) "
            + "OR (d.scope_type='PROJECT' AND d.scope_id=#{projectId}) "
            + "OR (d.scope_type='BUSINESS_LINE' AND d.scope_id=#{businessLineId})) "
            + "ORDER BY score DESC LIMIT #{limit}")
    List<Map<String, Object>> search(@Param("q") String q,
                                     @Param("userId") Long userId,
                                     @Param("projectId") Long projectId,
                                     @Param("businessLineId") Long businessLineId,
                                     @Param("limit") int limit);

    /** 删除某文档的全部切片（内容变更后重建用，避免旧切片继续被召回）。 */
    @Delete("DELETE FROM kb_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /** 某文档的切片数：入库自检与单测断言用。 */
    @Select("SELECT COUNT(1) FROM kb_chunk WHERE document_id = #{documentId}")
    int countByDocumentId(@Param("documentId") Long documentId);
}
