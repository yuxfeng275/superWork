package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiConnectorIdentity;
import com.bu.management.mapper.AiConnectorIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 连接器用户身份解析：本地用户 → 外部系统身份 ID。
 * v1 只读映射表，不做自动匹配兜底；查不到返回 null，由工具层转 isError。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Service
@RequiredArgsConstructor
public class AiConnectorIdentityService {

    public static final String CONNECTOR_OA = "oa";
    public static final String CONNECTOR_WORKTIME = "worktime";
    public static final String CONNECTOR_YUNXIAO = "yunxiao";

    private final AiConnectorIdentityMapper mapper;

    /**
     * 解析用户在指定连接器的外部身份 ID；未映射返回 null。
     */
    public String resolve(Long userId, String connectorCode) {
        AiConnectorIdentity identity = mapper.selectOne(
                new LambdaQueryWrapper<AiConnectorIdentity>()
                        .eq(AiConnectorIdentity::getUserId, userId)
                        .eq(AiConnectorIdentity::getConnectorCode, connectorCode));
        return identity == null || !StringUtils.hasText(identity.getExternalId())
                ? null : identity.getExternalId();
    }
}
