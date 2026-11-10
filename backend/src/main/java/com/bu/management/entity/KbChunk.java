package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识库切片：检索与注入模型的最小单位。
 *
 * <p>一块正文上限 600 字、相邻块重叠 80 字：块太大会让 ngram 相关度被稀释，
 * 不留重叠则跨块的句子两边都召不全。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Data
@TableName("kb_chunk")
public class KbChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    /** 块序号，从 0 递增，用于还原原文顺序。 */
    private Integer ordinal;

    /** 所属小节标题（最近的 # 行 /「一、」行），可空。 */
    private String heading;

    private String content;

    /** 正文字符数，与 content 一起落库便于排查超长块。 */
    private Integer charLen;

    private LocalDateTime createdAt;
}
