package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.KbDocument;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库文档读写入口。
 *
 * <p>增量同步只按 (sourceType, sourceRef) 反查旧文档，所以单独写死一条 SQL：
 * 走唯一键 uk_kb_doc_source，比拼 LambdaQueryWrapper 更稳定也更好解释。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /** 按幂等键取文档；sourceRef 为空的来源（无幂等键）永远查不到，每次入库都是新文档。 */
    @Select("SELECT * FROM kb_document WHERE source_type = #{sourceType} AND source_ref = #{sourceRef} "
            + "ORDER BY id DESC LIMIT 1")
    KbDocument findBySourceRef(@Param("sourceType") String sourceType, @Param("sourceRef") String sourceRef);

    /**
     * 按来源聚合健康度：总量 / 启用 / 停用 / 过期未更新 / 最近更新时间。
     *
     * <p>过期口径固定 180 天（与事实保留期同口径，但不是删除）：只用来提示人工复核，
     * 不自动下线——知识库文档该不该下线是业务判断，不能由一条 SQL 替人决定。
     */
    @Select("SELECT source_type AS sourceType, COUNT(*) AS total, "
            + "SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabled, "
            + "SUM(CASE WHEN enabled = 0 THEN 1 ELSE 0 END) AS disabled, "
            + "SUM(CASE WHEN updated_at < DATE_SUB(NOW(), INTERVAL 180 DAY) THEN 1 ELSE 0 END) AS stale, "
            + "MAX(updated_at) AS lastUpdatedAt "
            + "FROM kb_document GROUP BY source_type ORDER BY total DESC")
    List<Map<String, Object>> statsBySource();

    /** 内容校验和重复的文档组数：同一内容被不同来源重复入库时，检索结果会被同文多条占满。 */
    @Select("SELECT COUNT(*) FROM (SELECT checksum FROM kb_document "
            + "WHERE checksum IS NOT NULL AND checksum <> '' GROUP BY checksum HAVING COUNT(*) > 1) t")
    int countDuplicateChecksums();
}
