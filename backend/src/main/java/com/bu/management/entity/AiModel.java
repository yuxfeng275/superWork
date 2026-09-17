package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 模型注册表：一行 = 某个提供方（连接器）下的一个可用模型。
 * 与连接器的分界：连接器管「连接」（地址/凭据/启停/测试），本表管「模型」（模型名/用途/默认）。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Data
@TableName("ai_model")
public class AiModel {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提供方连接器编码（deepseek / glm / ...） */
    private String providerCode;

    /** API 模型名（传给模型的 model 参数） */
    private String model;

    /** 展示名（管理页与助手下拉） */
    private String displayName;

    /** 可用于 AI 助手 */
    private Integer assistantEnabled;

    /** 用于邮件摘要 / 周报纪要 */
    private Integer digestEnabled;

    /** AI 助手默认模型（全局唯一） */
    private Integer isDefault;

    private Integer enabled;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
