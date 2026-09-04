package com.bu.management.service;

import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.entity.AiConnector;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiConnectorMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通用 AI 连接器注册表服务：CRUD（凭据 AES 加密）、连接测试分发、
 * 供 AI 工具层动态生成 query/read 工具的查询入口。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConnectorRegistryService {

    public static final String AUTH_BASIC = "BASIC";
    public static final String AUTH_TOKEN = "TOKEN";
    public static final String AUTH_MCP = "MCP";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final String DEFAULT_TEST_PATH = "/api/v1/auth/login";

    private final AiConnectorMapper mapper;
    private final EmailCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 管理端列表项：凭据只回 configured 布尔，绝不回显。 */
    public record ConnectorView(Long id, String code, String name, String authType, String baseUrl,
            String mcpUrl, String testPath, String queryPath, String readPath,
            boolean usernameConfigured, boolean passwordConfigured, boolean tokenConfigured,
            boolean enabled, String lastTestStatus, String lastTestMessage,
            LocalDateTime lastTestedAt, boolean builtIn, int sortOrder) {}

    public record ConnectorSaveRequest(String code, String name, String authType, String baseUrl,
            String mcpUrl, String testPath, String queryPath, String readPath,
            String username, String password, String token, Boolean enabled, Integer sortOrder) {}

    public List<ConnectorView> list() {
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiConnector>()
                        .orderByAsc(AiConnector::getSortOrder)
                        .orderByAsc(AiConnector::getId))
                .stream().map(this::toView).toList();
    }

    public ConnectorView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public ConnectorView create(ConnectorSaveRequest request) {
        validate(request);
        AiConnector entity = new AiConnector();
        applyRequest(entity, request);
        if (mapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiConnector>()
                .eq(AiConnector::getCode, entity.getCode())) > 0) {
            throw new IllegalArgumentException("连接器编码已存在：" + entity.getCode());
        }
        entity.setBuiltIn(0);
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public ConnectorView update(Long id, ConnectorSaveRequest request) {
        AiConnector entity = require(id);
        // code 不允许修改（工具名已生成）
        AiConnector updated = new AiConnector();
        updated.setId(entity.getId());
        if (StringUtils.hasText(request.name())) updated.setName(request.name().trim());
        if (StringUtils.hasText(request.authType())) updated.setAuthType(normalizeAuth(request.authType()));
        if (StringUtils.hasText(request.baseUrl())) updated.setBaseUrl(trimSlash(request.baseUrl()));
        updated.setMcpUrl(AUTH_MCP.equals(updated.getAuthType()) || StringUtils.hasText(request.mcpUrl())
                ? trimSlash(request.mcpUrl()) : entity.getMcpUrl());
        if (StringUtils.hasText(request.testPath())) updated.setTestPath(request.testPath().trim());
        if (request.queryPath() != null) updated.setQueryPath(request.queryPath().trim());
        if (request.readPath() != null) updated.setReadPath(request.readPath().trim());
        if (StringUtils.hasText(request.username())) updated.setEncryptedUsername(cipher.encrypt(request.username()));
        if (StringUtils.hasText(request.password())) updated.setEncryptedPassword(cipher.encrypt(request.password()));
        if (StringUtils.hasText(request.token())) updated.setEncryptedToken(cipher.encrypt(request.token()));
        if (request.enabled() != null) updated.setEnabled(request.enabled() ? 1 : 0);
        if (request.sortOrder() != null) updated.setSortOrder(request.sortOrder());
        mapper.updateById(updated);
        return toView(require(id));
    }

    @Transactional
    public void delete(Long id) {
        AiConnector entity = require(id);
        if (Integer.valueOf(1).equals(entity.getBuiltIn())) {
            throw new IllegalArgumentException("内置连接器不可删除，可停用");
        }
        mapper.deleteById(id);
    }

    /**
     * 连接测试：BASIC → POST base_url + test_path {username,password}（HTTP 2xx 即成功）；
     * TOKEN → GET base_url 携带 Bearer；MCP → 委托 MCP 客户端 tools/list。
     */
    public ConnectorView test(Long id) {
        AiConnector entity = require(id);
        String message;
        boolean success;
        try {
            switch (entity.getAuthType()) {
                case AUTH_BASIC -> testBasic(entity);
                case AUTH_TOKEN -> testToken(entity);
                case AUTH_MCP -> testMcp(entity);
                default -> throw new IllegalStateException("不支持的认证类型：" + entity.getAuthType());
            }
            success = true;
            message = "连接成功";
        } catch (RuntimeException e) {
            success = false;
            message = sanitize(e.getMessage());
            log.info("AI 连接器测试失败: code={}, message={}", entity.getCode(), message);
        }
        AiConnector update = new AiConnector();
        update.setId(entity.getId());
        update.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        update.setLastTestMessage(message.length() > 500 ? message.substring(0, 500) : message);
        update.setLastTestedAt(LocalDateTime.now());
        mapper.updateById(update);
        ConnectorView view = toView(require(id));
        if (!success) {
            throw new IllegalStateException(message);
        }
        return view;
    }

    /** 启用中的连接器（供工具层动态注册）。 */
    public List<AiConnector> listEnabled() {
        return mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiConnector>()
                .eq(AiConnector::getEnabled, 1));
    }

    /** 凭据解密（仅 AI 工具执行路径使用，绝不进入工具输出）。 */
    public String credential(AiConnector entity, String kind) {
        return switch (kind) {
            case "username" -> StringUtils.hasText(entity.getEncryptedUsername())
                    ? cipher.decrypt(entity.getEncryptedUsername()) : null;
            case "password" -> StringUtils.hasText(entity.getEncryptedPassword())
                    ? cipher.decrypt(entity.getEncryptedPassword()) : null;
            case "token" -> StringUtils.hasText(entity.getEncryptedToken())
                    ? cipher.decrypt(entity.getEncryptedToken()) : null;
            default -> null;
        };
    }

    public String testUrl(AiConnector entity) {
        String path = StringUtils.hasText(entity.getTestPath()) ? entity.getTestPath() : DEFAULT_TEST_PATH;
        return trimSlash(entity.getBaseUrl()) + (path.startsWith("/") ? path : "/" + path);
    }

    public JsonNode getJson(AiConnector entity, String path, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(entity.getBaseUrl()) + (path.startsWith("/") ? path : "/" + path)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = httpClient.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("认证失败，请检查连接器凭据");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("外部系统暂时不可用(" + response.statusCode() + ")");
            }
            return objectMapper.readTree(response.body() == null ? "{}" : response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("外部系统暂时不可用，请稍后重试");
        }
    }

    // ==================== 内部 ====================

    private void testBasic(AiConnector entity) {
        String username = credential(entity, "username");
        String password = credential(entity, "password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("请先配置服务账号与密码");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        Map<String, Object> alt = new LinkedHashMap<>();
        alt.put("employee_no", username);
        alt.put("password", password);
        postJsonAccept2xx(entity, body);
    }

    private void testToken(AiConnector entity) {
        String token = credential(entity, "token");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("请先配置访问 Token");
        }
        getJson(entity, "/", token);
    }

    private void testMcp(AiConnector entity) {
        String token = credential(entity, "token");
        if (!StringUtils.hasText(entity.getMcpUrl())) {
            throw new IllegalStateException("请先配置 MCP 服务地址");
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("请先配置访问 Token");
        }
        // 最小 JSON-RPC tools/list 探活（与 YuqueMcpClient 相同协议）
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", 1);
            body.put("method", "tools/list");
            body.put("params", Map.of());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(entity.getMcpUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException("语雀认证失败，请检查访问 Token 配置".replace("语雀", entity.getName()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + " MCP 调用失败(" + response.statusCode() + ")");
            }
            if (!StringUtils.hasText(response.body())) {
                throw new IllegalStateException(entity.getName() + " MCP 响应为空");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + " 服务暂时不可用，请稍后重试");
        }
    }

    private void postJsonAccept2xx(AiConnector entity, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl(entity)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 400
                    || response.statusCode() == 422) {
                throw new IllegalStateException(entity.getName() + "认证失败，请检查账号密码");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + "暂时不可用(" + response.statusCode() + ")");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "暂时不可用，请稍后重试");
        }
    }

    private void validate(ConnectorSaveRequest request) {
        if (!StringUtils.hasText(request.code()) || !CODE_PATTERN.matcher(request.code()).matches()) {
            throw new IllegalArgumentException("编码必须为 2-32 位小写字母/数字/下划线，且以字母开头");
        }
        normalizeAuth(request.authType());
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new IllegalArgumentException("服务地址不能为空");
        }
        if (!request.baseUrl().startsWith("http://") && !request.baseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("服务地址必须是 HTTP(S) 地址");
        }
    }

    private String normalizeAuth(String authType) {
        if (!AUTH_BASIC.equals(authType) && !AUTH_TOKEN.equals(authType) && !AUTH_MCP.equals(authType)) {
            throw new IllegalArgumentException("认证类型必须是 BASIC / TOKEN / MCP");
        }
        return authType;
    }

    private void applyRequest(AiConnector entity, ConnectorSaveRequest request) {
        entity.setCode(request.code().trim());
        entity.setName(StringUtils.hasText(request.name()) ? request.name().trim() : request.code().trim());
        entity.setAuthType(normalizeAuth(request.authType()));
        entity.setBaseUrl(trimSlash(request.baseUrl()));
        entity.setMcpUrl(trimSlash(request.mcpUrl()));
        entity.setTestPath(StringUtils.hasText(request.testPath()) ? request.testPath().trim() : DEFAULT_TEST_PATH);
        entity.setQueryPath(request.queryPath() == null ? null : request.queryPath().trim());
        entity.setReadPath(request.readPath() == null ? null : request.readPath().trim());
        if (StringUtils.hasText(request.username())) entity.setEncryptedUsername(cipher.encrypt(request.username()));
        if (StringUtils.hasText(request.password())) entity.setEncryptedPassword(cipher.encrypt(request.password()));
        if (StringUtils.hasText(request.token())) entity.setEncryptedToken(cipher.encrypt(request.token()));
        entity.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
    }

    private ConnectorView toView(AiConnector entity) {
        return new ConnectorView(entity.getId(), entity.getCode(), entity.getName(), entity.getAuthType(),
                entity.getBaseUrl(), entity.getMcpUrl(), entity.getTestPath(), entity.getQueryPath(),
                entity.getReadPath(),
                StringUtils.hasText(entity.getEncryptedUsername()),
                StringUtils.hasText(entity.getEncryptedPassword()),
                StringUtils.hasText(entity.getEncryptedToken()),
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getLastTestStatus(), entity.getLastTestMessage(), entity.getLastTestedAt(),
                Integer.valueOf(1).equals(entity.getBuiltIn()),
                entity.getSortOrder() == null ? 100 : entity.getSortOrder());
    }

    /**
     * BASIC 登录：POST test_path {username,password}，
     * 返回响应中的 token/access_token 字段（兼容 data.token 包装）；
     * 2xx 但无 token 字段时返回空串（接口可达即视为凭据有效）。
     */
    public String basicLogin(AiConnector entity, String username, String password) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("password", password);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl(entity)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403
                    || response.statusCode() == 400 || response.statusCode() == 422) {
                throw new IllegalStateException(entity.getName() + "认证失败，请检查账号密码");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + "暂时不可用(" + response.statusCode() + ")");
            }
            JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
            return root.path("token").asText(root.path("access_token").asText(
                    root.path("data") == null ? "" : root.path("data").path("token").asText("")));
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "暂时不可用，请稍后重试");
        }
    }

    private AiConnector require(Long id) {
        AiConnector entity = mapper.selectById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("连接器不存在");
        }
        return entity;
    }

    private String trimSlash(String value) {
        return value == null ? null : (value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }

    private String sanitize(String message) {
        if (message == null) return "未知错误";
        String cleaned = message.replaceAll(
                "(?i)(token|password|secret|api[_-]?key)=[^&\\s,;\"']+", "$1=***");
        return cleaned;
    }
}
