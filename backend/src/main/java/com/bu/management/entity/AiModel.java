package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 模型注册表：一行 = 一个可调用的模型。
 * 可自带协议 / 地址 / API Key（中转站、官方 OpenAI 兼容）；未填时回落同名连接器。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Data
@TableName("ai_model")
public class AiModel {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提供方编码（deepseek / glm / openai / 自定义）；连接器同名时作回落 */
    private String providerCode;

    /** 接入协议：openai-compat（对话/摘要）/ typesafe（Jev 决策） */
    private String apiProtocol;

    /** API 模型名（传给模型的 model 参数） */
    private String model;

    /** 展示名（管理页与助手下拉） */
    private String displayName;

    /** 模型自己的接口地址；空则回落同名连接器 */
    private String baseUrl;

    /** 模型自己的 API Key（AES）；空则回落同名连接器 */
    private String encryptedApiKey;

    /** 可用于 AI 助手 */
    private Integer assistantEnabled;

    /** 用于邮件摘要 / 周报纪要 */
    private Integer digestEnabled;

    /** 用于 AI 助手意图路由与写操作门禁（System One / Jev） */
    private Integer decisionEnabled;

    /** AI 助手默认模型（全局唯一） */
    private Integer isDefault;

    private Integer enabled;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
