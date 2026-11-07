package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目智能体闭环（agent-autoloop 桥）配置。
 * 令牌只用于 /internal/project-context/** 内部端点，业务接口仍走登录用户身份。
 */
@Data
@Component
@ConfigurationProperties(prefix = "project-context")
public class ProjectContextProperties {

    /** 内部接口令牌；空表示不校验（开发环境） */
    private String token = "";

    /** 单次汇入每个来源最多拉取的行数，防止首次汇入被历史数据淹没 */
    private int maxSourceRows = 200;

    /** 手工投递（文件/说明/聊天）的落盘根目录 */
    private String factStorageDir = "/data/project-facts";

    /** 手工投递单文件大小上限（MB） */
    private int factMaxFileMb = 50;
}
