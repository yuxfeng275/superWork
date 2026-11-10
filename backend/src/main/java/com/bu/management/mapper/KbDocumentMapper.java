package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.KbDocument;
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
}
