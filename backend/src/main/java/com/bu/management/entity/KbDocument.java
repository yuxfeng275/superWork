package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库文档：一次入库一行，(sourceType, sourceRef) 是幂等键。
 *
 * <p>{@code checksum} 存内容 sha256：同一来源再次入库且内容没变时直接返回旧文档，
 * 不删切片也不重建，避免每日同步把全文索引反复打散。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Data
@TableName("kb_document")
public class KbDocument {

    /** 来源：人工维护（后台表单 / 接口直传）。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 可见范围：全公司（默认，也是唯一的「免 scopeId」范围）。 */
    public static final String SCOPE_COMPANY = "COMPANY";

    /** 可见范围：业务线，需显式传 scopeId。 */
    public static final String SCOPE_BUSINESS_LINE = "BUSINESS_LINE";

    /** 可见范围：项目，需显式传 scopeId。 */
    public static final String SCOPE_PROJECT = "PROJECT";

    /** 可见范围：个人，只有 owner 本人能检索到。 */
    public static final String SCOPE_OWNER = "OWNER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    /** UPLOAD / YUQUE / CASE / MANUAL。 */
    private String sourceType;

    /** 来源内唯一标识；为空表示没有幂等键（MySQL 唯一键允许多条 NULL）。 */
    private String sourceRef;

    /** COMPANY / BUSINESS_LINE / PROJECT / OWNER。 */
    private String scopeType;

    /** 业务线 ID / 项目 ID / 用户 ID，语义由 scopeType 决定。 */
    private Long scopeId;

    private String businessLine;

    /** 逗号分隔标签，仅展示与筛选，不参与鉴权。 */
    private String tags;

    /** 内容 sha256（标题 + 正文），用于增量跳过。 */
    private String checksum;

    /** 1 = 参与检索，0 = 下线留档。 */
    private Integer enabled;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
