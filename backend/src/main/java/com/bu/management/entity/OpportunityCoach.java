package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 商机 AI 教练建议快照：一次生成一行，<b>只读建议</b>，不参与商机字段计算。
 *
 * <p>触发源三种（{@code trigger_source}）：
 * <ul>
 *   <li>{@link #SOURCE_INBOUND}：商机问句收到回复后自动入队（带 {@code promptId}）；</li>
 *   <li>{@link #SOURCE_MANUAL}：详情页点「重新生成」（{@code promptId} 为空）；</li>
 *   <li>{@link #SOURCE_SCHEDULED}：定时任务扫描待跟进商机后入队。</li>
 * </ul>
 *
 * <p>{@code content_json} 是模型返回的整包 JSON（readiness/diagnosis/risks/questions/solutions/
 * nextActions/probabilitySuggestion），前端只读展示，采纳动作另外写跟进记录。
 *
 * @author BU Team
 * @since 2026-12-01
 */
@Data
@TableName("opportunity_coach")
public class OpportunityCoach {

    /** 已入队，等线程池开跑。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 正在调用模型。 */
    public static final String STATUS_GENERATING = "GENERATING";
    /** 生成成功，{@code content_json} 可用。 */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 生成失败，原因见 {@code error}。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 问句回复入站触发。 */
    public static final String SOURCE_INBOUND = "INBOUND";
    /** 人工点按钮触发。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 定时任务触发。 */
    public static final String SOURCE_SCHEDULED = "SCHEDULED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long opportunityId;

    /** 触发的问句 ID（手动生成时为空）。 */
    private Long promptId;

    /** 触发源：INBOUND / MANUAL / SCHEDULED。 */
    private String triggerSource;

    /** 状态：PENDING / GENERATING / SUCCESS / FAILED。 */
    private String status;

    /** AI 教练包 JSON 原文。 */
    private String contentJson;

    /** 实际使用的模型提供方代码（失败为空）。 */
    private String provider;

    /** 实际使用的模型名（失败为空）。 */
    private String model;

    /** 失败原因（截 500 字）。 */
    private String error;

    /** 触发人用户 ID（配额按它计数）。 */
    private Long requestedBy;

    private LocalDateTime createdAt;

    private LocalDateTime generatedAt;
}
