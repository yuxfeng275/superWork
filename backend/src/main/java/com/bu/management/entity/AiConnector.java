package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 连接器注册表：一行 = 一个外部系统连接实例。
 * auth_type 决定凭据形态：BASIC（账号密码）/ TOKEN / MCP（Bearer over MCP 协议）。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Data
@TableName("ai_connector")
public class AiConnector {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 唯一编码（slug），作为工具名前缀 */
    private String code;

    /** 显示名 */
    private String name;

    /** BASIC | TOKEN | MCP */
    private String authType;

    /** 服务根地址 */
    private String baseUrl;

    /** MCP 端点（auth_type=MCP） */
    private String mcpUrl;

    /** 连接测试路径（BASIC，默认 /api/v1/auth/login 形态） */
    private String testPath;

    /** 查询接口路径（通用工具） */
    private String queryPath;

    /** 读取接口路径（通用工具） */
    private String readPath;

    private String encryptedUsername;
    private String encryptedPassword;
    private String encryptedToken;

    private Integer enabled;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime lastTestedAt;

    /** 内置连接器不可删除 */
    private Integer builtIn;

    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
