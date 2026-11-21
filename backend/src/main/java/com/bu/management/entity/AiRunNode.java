package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * SOP 的单个节点在其 run 里的执行状态。
 *
 * <p>{@code deps} 是前驱 node_key 的 JSON 数组，推进条件为「所有前驱 SUCCEEDED」；
 * {@code epoch} 与 run 同步，防止被接管后的旧 worker 把状态改回去。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("ai_run_node")
public class AiRunNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    /** SOP 内的节点标识，run 内唯一 */
    private String nodeKey;

    private Integer seq;

    /** sidecar / dsh-acp / cli */
    private String executorKind;

    private String status;

    private Integer attempt;

    private Integer maxAttempt;

    /** gate 打回次数 */
    private Integer reworkCount;

    private Integer epoch;

    /** JSON 数组：前驱 node_key */
    private String deps;

    private Integer gateRequired;

    /** 执行器会话号（dsh: session id；sidecar: 会话 id），用于续跑 */
    private String executorSessionId;

    private String inputJson;

    private String outputJson;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
