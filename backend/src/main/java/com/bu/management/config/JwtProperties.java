package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * JWT 密钥
     */
    private String secret;

    /**
     * JWT 过期时间（毫秒）
     */
    private Long expiration;

    /**
     * JWT 刷新令牌过期时间（毫秒）
     */
    private Long refreshExpiration;
}
