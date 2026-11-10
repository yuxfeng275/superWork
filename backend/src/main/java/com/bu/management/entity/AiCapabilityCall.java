package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 能力网关调用日志：一次能力调用一行，只记元数据不记业务内容。
 *
 * <p>存在的意义是回答两个问题：某个能力到底有没有人在调、失败率与耗时是多少。
 * 写库失败绝不影响调用本身（网关里只 log.warn），所以这张表允许丢行。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Data
@TableName("ai_capability_call")
public class AiCapabilityCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 能力编码，如 op.coach / skill.weekly。 */
    private String capabilityId;

    /** 调用人用户 ID，系统 / 定时任务为空。 */
    private Long userId;

    /** 关联的 AI 运行 ID（可选）。 */
    private String runId;

    /** 是否成功。 */
    private Boolean ok;

    /** 耗时毫秒。 */
    private Integer latencyMs;

    /** 失败错误码（成功为空）。 */
    private String errorCode;

    private LocalDateTime createdAt;
}
