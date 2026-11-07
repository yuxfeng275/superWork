package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 项目绑定的代码仓库：夜间代码类任务（dsh / command 通道）的落点。
 * 先只做绑定与基础设置，PR 范围与审批人后续再定（见方案 §4.4）。
 */
@Data
@TableName("project_git_repo")
public class ProjectGitRepo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String name;

    /** HTTPS 仓库地址 */
    private String repoUrl;

    private String defaultBranch;

    /** 允许 agent 改动的目录（如 sql/），空 = 只读 */
    private String workDir;

    /** dsh / command / claude-code */
    private String agent;

    /** 凭据引用（不入库明文） */
    private String credentialRef;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
