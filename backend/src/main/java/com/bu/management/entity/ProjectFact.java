package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 项目事实：项目唯一事实源的一条记录。
 *
 * <p>source_type + source_ref 指向原始出处（会议/邮件/工作项/大事儿…），
 * 任何人看到一条结论都能点回证据；dedupe_hash 保证同一源对象反复汇入只更新不重复。
 * body 是原文，只作资料用，绝不拼进提示词（指令数据分离）。
 */
@Data
@TableName("project_fact")
public class ProjectFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** MEETING / EMAIL / WORKITEM / KEY_MATTER / QUOTATION / FILE_UPLOAD / CHAT_EXPORT / GIT */
    private String sourceType;

    /** 源主键或 URL */
    private String sourceRef;

    private Long actorId;

    private LocalDateTime happenedAt;

    private String title;

    private String body;

    private String digest;

    /** PROJECT / PRIVATE_COMPANY / OWNER */
    private String visibility;

    /** sha256(source_type|source_ref) */
    private String dedupeHash;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
