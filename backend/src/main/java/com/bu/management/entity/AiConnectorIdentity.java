package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 连接器用户身份映射：本地用户 → 外部系统身份 ID。
 * 连接器工具据此把“当前登录用户”翻译为外部系统中的身份。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Data
@TableName("ai_connector_identity")
public class AiConnectorIdentity {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 本地系统用户 ID */
    private Long userId;

    /** 连接器编码（oa/worktime/yunxiao） */
    private String connectorCode;

    /** 外部系统身份 ID */
    private String externalId;

    /** 外部系统显示名（缓存） */
    private String displayName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
