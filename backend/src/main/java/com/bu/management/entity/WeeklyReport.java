package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("weekly_report")
public class WeeklyReport {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_GENERATION_FAILED = "GENERATION_FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 本周周一 */
    private LocalDate weekStartDate;
    /** 本周周五 */
    private LocalDate periodEndDate;
    /** 企微智能总结（粘贴） */
    private String wecomSummary;
    /** 人为补充信息 */
    private String manualNotes;
    /** 生成提示词：引导 AI 生成周报的侧重点 */
    private String generationPrompt;
    /** 自动采集事实（大事儿+财务+上周闭环） */
    private String autoFactsJson;
    /** 本周核心工作完成情况 */
    private String coreWork;
    /** KPI相关情况（财务/业务/提效/品质） */
    private String kpiSection;
    /** 问题/风险与解决办法 */
    private String risks;
    /** 下周工作计划 */
    private String nextWeekPlan;
    /** 周会纪要（Markdown） */
    private String minutesMarkdown;
    /** PENDING|GENERATING|DRAFT|CONFIRMED|PUBLISHED|GENERATION_FAILED */
    private String status;
    private String generationModel;
    /** 生成使用的模型提供方编码（如 glm / deepseek） */
    private String generationProvider;
    /** AI|RULES|NONE */
    private String generationMode;
    /**
     * 上次生成失败的原因。生成成功时必须能写回 NULL，
     * 所以显式指定 ALWAYS（默认 NOT_NULL 会把 null 跳过，旧错误文案永远清不掉）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String generationError;
    private Integer yuqueDocId;
    private String yuqueDocSlug;
    private String yuqueDocUrl;
    /** VERIFIED|MOVED|NOT_FOUND */
    private String yuqueTocStatus;
    /** PENDING|MANUAL_DONE|API_FAILED */
    private String sheetSyncStatus;
    private LocalDateTime sheetSyncedAt;
    private String wecomPushStatus;
    private LocalDateTime wecomPushedAt;
    private Long createdBy;
    private Long publishedBy;
    private LocalDateTime createdAt;
    /**
     * 仅由数据库 ON UPDATE CURRENT_TIMESTAMP 维护。
     * 若让 MyBatis 把读出来的旧值回写进 SET 子句，会显式赋值旧的 updated_at，
     * MySQL 的自动更新时间不再生效，updated_at 会永远停在建行那一刻。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
