package com.bu.management.service;

import com.bu.management.entity.AiConnector;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用连接器 HTTP 客户端：以注册连接器的凭据调用外部系统只读接口。
 * BASIC → 登录换取 token（内存缓存）；TOKEN/MCP → Bearer 直调。
 * 所有错误消息不含凭据。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Component
@RequiredArgsConstructor
public class GenericConnectorClient {

    private final AiConnectorRegistryService registryService;
    private final Object tokenLock = new Object();
    private volatile Long cachedConnectorId;
    private volatile String cachedToken;

    /** GET 外部接口：自动携带认证（BASIC 登录缓存 / Bearer）。 */
    public JsonNode getJson(AiConnector connector, String path) {
        String token = resolveToken(connector);
        try {
            return registryService.getJson(connector, path, token);
        } catch (IllegalStateException e) {
            // 认证失败时 BASIC 重登一次
            if (AiConnectorRegistryService.AUTH_BASIC.equals(connector.getAuthType())
                    && String.valueOf(e.getMessage()).contains("认证失败")) {
                synchronized (tokenLock) {
                    cachedToken = null;
                    cachedConnectorId = null;
                }
                token = login(connector);
                return registryService.getJson(connector, path, token);
            }
            throw e;
        }
    }

    /** 把 JSON 数组/对象压平为模型可读的行集合。 */
    public List<Map<String, Object>> toRows(JsonNode data) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode items = data;
        if (items == null) return rows;
        if (items.has("data")) items = items.get("data");
        if (items.has("list")) items = items.get("list");
        if (items.has("items")) items = items.get("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                rows.add(toRow(item));
                if (rows.size() >= 50) break;
            }
        } else if (items.isObject()) {
            rows.add(toRow(items));
        }
        return rows;
    }

    private Map<String, Object> toRow(JsonNode item) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (item == null) return row;
        if (item.has("id")) row.put("id", item.path("id").asText(""));
        for (String field : List.of("title", "name", "subject", "status", "status_label", "project_name",
                "summary", "created_at", "updated_at", "date", "employee_name")) {
            if (item.hasNonNull(field)) {
                row.put(field, item.path(field).asText(""));
            }
        }
        if (row.isEmpty()) {
            // 兜底：取前 6 个字段
            item.fields().forEachRemaining(entry -> {
                if (row.size() < 6 && entry.getValue().isValueNode()) {
                    row.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
        }
        return row;
    }

    /** BASIC 登录缓存；TOKEN/MCP 直接返回 Bearer。 */
    private String resolveToken(AiConnector connector) {
        String kind = connector.getAuthType();
        if (AiConnectorRegistryService.AUTH_BASIC.equals(kind)) {
            synchronized (tokenLock) {
                if (cachedToken != null && connector.getId() != null
                        && connector.getId().equals(cachedConnectorId)) {
                    return cachedToken;
                }
                return login(connector);
            }
        }
        return registryService.credential(connector, "token");
    }

    private String login(AiConnector connector) {
        synchronized (tokenLock) {
            if (connector.getId() != null && connector.getId().equals(cachedConnectorId)
                    && cachedToken != null) {
                return cachedToken;
            }
            String username = registryService.credential(connector, "username");
            String password = registryService.credential(connector, "password");
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new IllegalStateException("请先在连接器管理中配置「" + connector.getName() + "」的服务账号");
            }
            String token = registryService.basicLogin(connector, username, password);
            cachedToken = token;
            cachedConnectorId = connector.getId();
            return token;
        }
    }
}
