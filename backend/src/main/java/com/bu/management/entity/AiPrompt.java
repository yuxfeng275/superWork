package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Prompt 版本库：同一 code 多版本共存，运行时取 enabled=1 的最大 version。
 *
 * <p>提示词外置的目的：改提示词不用发版。历史版本只追加不覆盖，
 * 便于「同一段提示词的 v3 比 v2 省 30% token」这类对比。</p>
 *
 * @author BU Team
 * @since 2026-10-22
 */
@Data
@TableName("ai_prompt")
public class AiPrompt {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 提示词编码（业务侧引用名，如 weekly-report-summary），非唯一，与 version 组合唯一。 */
    private String code;

    /** 版本号：同一 code 从 1 递增，由 PromptRegistry 插入时计算 max+1。 */
    private Integer version;

    /** 提示词正文（完整 systemPrompt 模板）。 */
    private String content;

    /** 模型偏好提示（可空）：如期望的 provider/model 关键字，仅供人工/调度参考。 */
    private String modelHint;

    /** 是否启用：1=启用（参与「取最大 version」），0=停用（历史版本保留但不被选中）。 */
    private Integer enabled;

    /** 操作人用户 ID：谁存的这个版本。 */
    private Long createdBy;

    /** 创建时间。 */
    private LocalDateTime createdAt;
}
