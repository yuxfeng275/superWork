package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.entity.Connector;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.ConnectorMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 连接器注册表服务：外部系统连接的唯一存储与唯一管理入口（CRUD、凭据 AES 加密、
 * 就绪状态判定、连接测试分发、供 AI 工具层动态生成 query/read 工具）。
 * 内置连接器（云效/工时/OA/语雀/邮件/DeepSeek/GLM/企业微信）由 V52/V79 预置，
 * 各业务域（同步、AI 工具、邮件、周报）统一从这里读取连接参数。
 *
 * @author BU Team
 * @since 2026-09-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorRegistryService {

    public static final String AUTH_BASIC = "BASIC";
    public static final String AUTH_TOKEN = "TOKEN";
    public static final String AUTH_MCP = "MCP";
    /** 致远 OA REST：POST /seeyon/rest/token {userName,password} → {id}。 */
    public static final String AUTH_SEEYON = "SEEYON";
    /** 企业微信：CorpId + Secret 换动态 token（与 SEEYON 同为两段式）。 */
    public static final String AUTH_WECOM = "WECOM";
    /** 企业邮箱：连接参数按用户绑定在 email_account，连接器只承载组织级参数。 */
    public static final String AUTH_MAIL = "MAIL";

    public static final String CODE_YUNXIAO = "yunxiao";
    public static final String CODE_WORKTIME = "worktime";
    public static final String CODE_OA = "oa";
    public static final String CODE_YUQUE = "yuque";
    public static final String CODE_MAIL = "mail";
    public static final String CODE_DEEPSEEK = "deepseek";
    public static final String CODE_GLM = "glm";
    public static final String CODE_WECOM = "wecom";

    private static final Set<String> AUTH_TYPES =
            Set.of(AUTH_BASIC, AUTH_TOKEN, AUTH_MCP, AUTH_SEEYON, AUTH_WECOM, AUTH_MAIL);
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern EXTRA_KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.-]{0,39}$");
    private static final String DEFAULT_TEST_PATH = "/api/v1/auth/login";

    private final ConnectorMapper mapper;
    private final EmailCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 管理端列表项：凭据只回 configured 布尔，绝不回显。 */
    public record ConnectorView(Long id, String code, String name, String authType, String baseUrl,
            String mcpUrl, String testPath, String queryPath, String readPath,
            Map<String, Object> extraConfig,
            boolean usernameConfigured, boolean passwordConfigured, boolean tokenConfigured,
            boolean enabled, boolean ready, String hint,
            String lastTestStatus, String lastTestMessage,
            LocalDateTime lastTestedAt, boolean builtIn, int sortOrder) {}

    public record ConnectorSaveRequest(String code, String name, String authType, String baseUrl,
            String mcpUrl, String testPath, String queryPath, String readPath,
            Map<String, Object> extraConfig,
            String username, String password, String token, Boolean enabled, Integer sortOrder) {}

    /** 连接器状态项（AI 助手连接器面板 / 连接器管理页共用）。 */
    public record ConnectorStatus(String code, String name, String status, String hint) {}

    public List<ConnectorView> list() {
        return ordered().stream().map(this::toView).toList();
    }

    public ConnectorView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public ConnectorView create(ConnectorSaveRequest request) {
        validate(request);
        Connector entity = new Connector();
        applyRequest(entity, request);
        if (mapper.selectCount(new LambdaQueryWrapper<Connector>()
                .eq(Connector::getCode, entity.getCode())) > 0) {
            throw new IllegalArgumentException("连接器编码已存在：" + entity.getCode());
        }
        entity.setBuiltIn(0);
        entity.setEnabled(Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        requireReadyIfEnabled(entity);
        mapper.insert(entity);
        return toView(entity);
    }

    @Transactional
    public ConnectorView update(Long id, ConnectorSaveRequest request) {
        Connector entity = require(id);
        // code 不允许修改（工具名已生成）
        Connector updated = new Connector();
        updated.setId(entity.getId());
        if (StringUtils.hasText(request.name())) updated.setName(request.name().trim());
        String authType = StringUtils.hasText(request.authType())
                ? normalizeAuth(request.authType()) : entity.getAuthType();
        updated.setAuthType(authType);
        if (StringUtils.hasText(request.baseUrl())) updated.setBaseUrl(trimSlash(request.baseUrl()));
        if (AUTH_MCP.equals(authType) || request.mcpUrl() != null) {
            updated.setMcpUrl(trimSlash(request.mcpUrl()));
        }
        if (request.testPath() != null) {
            updated.setTestPath(StringUtils.hasText(request.testPath()) ? request.testPath().trim() : null);
        }
        if (request.queryPath() != null) updated.setQueryPath(request.queryPath().trim());
        if (request.readPath() != null) updated.setReadPath(request.readPath().trim());
        if (StringUtils.hasText(request.username())) updated.setEncryptedUsername(cipher.encrypt(request.username()));
        if (StringUtils.hasText(request.password())) updated.setEncryptedPassword(cipher.encrypt(request.password()));
        if (StringUtils.hasText(request.token())) updated.setEncryptedToken(cipher.encrypt(request.token()));
        if (request.extraConfig() != null) {
            updated.setExtraConfig(mergeExtra(entity, request.extraConfig()));
        }
        if (request.enabled() != null) updated.setEnabled(request.enabled() ? 1 : 0);
        if (request.sortOrder() != null) updated.setSortOrder(request.sortOrder());
        // 就绪校验：仅在「本次请求开启连接器」时要求配置完整
        // （已启用但缺凭据的连接器允许继续修改其他字段）
        Connector merged = merge(entity, updated);
        if (Boolean.TRUE.equals(request.enabled()) && !Integer.valueOf(1).equals(entity.getEnabled())) {
            requireReadyIfEnabled(merged);
        }
        mapper.updateById(updated);
        return toView(require(id));
    }

    @Transactional
    public void delete(Long id) {
        Connector entity = require(id);
        if (Integer.valueOf(1).equals(entity.getBuiltIn())) {
            throw new IllegalArgumentException("内置连接器不可删除，可停用");
        }
        mapper.deleteById(id);
    }

    /**
     * 通用连接测试：BASIC → POST base_url + test_path {username,password}（HTTP 2xx 即成功）；
     * TOKEN → GET base_url + test_path 携带 Bearer；MCP → tools/list；
     * SEEYON → /seeyon/rest/token。内置连接器的专属探活见 ConnectorTestDispatcher。
     */
    public ConnectorView test(Long id) {
        Connector entity = require(id);
        String message;
        boolean success;
        try {
            message = switch (entity.getAuthType()) {
                case AUTH_BASIC -> { testBasic(entity); yield "连接成功"; }
                case AUTH_TOKEN -> { testToken(entity); yield "连接成功"; }
                case AUTH_SEEYON -> testSeeyon(entity);
                default -> throw new IllegalStateException("不支持的认证类型：" + entity.getAuthType());
            };
            success = true;
        } catch (RuntimeException e) {
            success = false;
            message = sanitize(e.getMessage());
            log.info("连接器测试失败: code={}, message={}", entity.getCode(), message);
        }
        recordTestResult(id, success, message);
        if (!success) {
            throw new IllegalStateException(message);
        }
        return toView(require(id));
    }

    /** 记录最近一次连接测试结果。 */
    public void recordTestResult(Long id, boolean success, String message) {
        Connector update = new Connector();
        update.setId(id);
        update.setLastTestStatus(success ? "SUCCESS" : "FAILED");
        String text = message == null ? "" : message;
        update.setLastTestMessage(text.length() > 500 ? text.substring(0, 500) : text);
        update.setLastTestedAt(LocalDateTime.now());
        mapper.updateById(update);
    }

    /** 启用中的连接器（供工具层动态注册）。 */
    public List<Connector> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<Connector>().eq(Connector::getEnabled, 1));
    }

    /** 按主键取连接器实体（业务适配层用）。 */
    public Connector entity(Long id) {
        return require(id);
    }

    /** 按编码取连接器（外部系统连接的唯一读取入口）。 */
    public Optional<Connector> findByCode(String code) {
        if (!StringUtils.hasText(code)) return Optional.empty();
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<Connector>()
                .eq(Connector::getCode, code)
                .last("LIMIT 1")));
    }

    /** 连接器状态列表（AI 助手面板与连接器管理页共用的唯一口径）。 */
    public List<ConnectorStatus> statuses() {
        List<ConnectorStatus> list = new ArrayList<>();
        for (Connector entity : ordered()) {
            list.add(new ConnectorStatus(entity.getCode(), entity.getName(), status(entity), hint(entity)));
        }
        return list;
    }

    /** READY / DISABLED / NOT_CONFIGURED。 */
    public String status(Connector entity) {
        if (!Integer.valueOf(1).equals(entity.getEnabled())) return "DISABLED";
        return missingRequirement(entity) == null ? "READY" : "NOT_CONFIGURED";
    }

    /** 凭据解密（仅业务执行路径使用，绝不进入工具输出）。 */
    public String credential(Connector entity, String kind) {
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

    /** 扩展参数读取；无值返回 null。 */
    public String extra(Connector entity, String key) {
        Object value = extraMap(entity).get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 扩展参数读取，带默认值。 */
    public String extra(Connector entity, String key, String fallback) {
        String value = extra(entity, key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    /** 扩展参数整表。 */
    public Map<String, Object> extraMap(Connector entity) {
        if (entity == null || !StringUtils.hasText(entity.getExtraConfig())) return Map.of();
        try {
            Map<String, Object> parsed = objectMapper.readValue(entity.getExtraConfig(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("连接器扩展参数解析失败: code={}", entity.getCode());
            return Map.of();
        }
    }

    public String testUrl(Connector entity) {
        String path = StringUtils.hasText(entity.getTestPath()) ? entity.getTestPath() : DEFAULT_TEST_PATH;
        return trimSlash(entity.getBaseUrl()) + (path.startsWith("/") ? path : "/" + path);
    }

    public JsonNode getJson(Connector entity, String path, String token) {
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

    /** POST JSON 并按 Bearer 认证；返回响应体（2xx 之外抛业务异常）。 */
    public JsonNode postJson(Connector entity, String path, Map<String, Object> body, String token) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(entity.getBaseUrl()) + (path.startsWith("/") ? path : "/" + path)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (StringUtils.hasText(token)) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = httpClient.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403
                    || response.statusCode() == 400 || response.statusCode() == 422) {
                throw new IllegalStateException(entity.getName() + "认证失败，请检查凭据");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + "暂时不可用(" + response.statusCode() + ")");
            }
            String text = response.body();
            return objectMapper.readTree(text == null || text.isBlank() ? "{}" : text);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "暂时不可用，请稍后重试");
        }
    }

    /**
     * BASIC 登录：POST test_path {username,password}，
     * 返回响应中的 token/access_token 字段（兼容 data.token 包装）；
     * 2xx 但无 token 字段时返回空串（接口可达即视为凭据有效）。
     */
    public String basicLogin(Connector entity, String username, String password) {
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

    // ==================== 内部 ====================

    private List<Connector> ordered() {
        return mapper.selectList(new LambdaQueryWrapper<Connector>()
                .orderByAsc(Connector::getSortOrder)
                .orderByAsc(Connector::getId));
    }

    /** 单条就绪判定：返回缺失项提示（null = 已就绪）。 */
    private String missingRequirement(Connector entity) {
        boolean hasUsername = StringUtils.hasText(entity.getEncryptedUsername());
        boolean hasPassword = StringUtils.hasText(entity.getEncryptedPassword());
        boolean hasToken = StringUtils.hasText(entity.getEncryptedToken());
        boolean hasBaseUrl = StringUtils.hasText(entity.getBaseUrl());
        String code = entity.getCode() == null ? "" : entity.getCode();
        return switch (code) {
            case CODE_YUNXIAO -> !hasBaseUrl ? "缺少服务地址"
                    : !hasToken ? "缺少个人访问令牌"
                    : (!"region".equalsIgnoreCase(extra(entity, "edition", "center"))
                            && !StringUtils.hasText(extra(entity, "organizationId")) ? "缺少组织 ID" : null);
            case CODE_WORKTIME -> !hasBaseUrl ? "缺少服务地址"
                    : (!hasUsername || !hasPassword) ? "缺少服务账号与密码" : null;
            case CODE_OA -> !hasBaseUrl ? "缺少服务地址"
                    : (!hasToken && (!hasUsername || !hasPassword)) ? "缺少服务账号密码或令牌" : null;
            case CODE_YUQUE -> !StringUtils.hasText(entity.getMcpUrl()) ? "缺少 MCP 服务地址"
                    : !hasToken ? "缺少访问 Token" : null;
            case CODE_MAIL -> null;
            case CODE_DEEPSEEK, CODE_GLM -> !hasBaseUrl ? "缺少服务地址"
                    : !hasToken ? "缺少 API Key" : null;
            case CODE_WECOM -> !hasBaseUrl ? "缺少服务地址"
                    : !hasToken ? "缺少 Secret"
                    : !StringUtils.hasText(extra(entity, "corpId")) ? "缺少 CorpId"
                    : !StringUtils.hasText(extra(entity, "agentId")) ? "缺少 AgentId" : null;
            default -> switch (entity.getAuthType()) {
                case AUTH_MAIL -> null;
                case AUTH_BASIC -> (!hasUsername || !hasPassword) ? "缺少账号密码" : null;
                case AUTH_TOKEN, AUTH_MCP -> !hasToken ? "缺少访问 Token" : null;
                case AUTH_SEEYON -> (!hasToken && (!hasUsername || !hasPassword)) ? "缺少账号密码或令牌" : null;
                case AUTH_WECOM -> !hasToken ? "缺少 Secret" : null;
                default -> "认证类型不支持";
            };
        };
    }

    private String hint(Connector entity) {
        String code = entity.getCode() == null ? "" : entity.getCode();
        if (!Integer.valueOf(1).equals(entity.getEnabled())) {
            return "已停用，可在「连接器管理」启用";
        }
        String missing = missingRequirement(entity);
        if (missing != null) {
            return missing + "，请在「连接器管理」补全";
        }
        return switch (code) {
            case CODE_MAIL -> "邮箱账号按用户绑定，可在「邮件管理」为成员绑定邮箱并逐个测试";
            case CODE_YUNXIAO -> "已就绪，可查询/同步云效工作项";
            case CODE_WORKTIME -> "已就绪，可查询工时并执行合同/月度同步";
            case CODE_OA -> "已就绪，可查询待办/已办并同步组织与合同";
            case CODE_YUQUE -> "已就绪，可检索语雀文档";
            case CODE_DEEPSEEK, CODE_GLM -> "已就绪，AI 助手与邮件摘要可用";
            case CODE_WECOM -> "已就绪，可推送企业微信通知";
            default -> "已就绪";
        };
    }

    private void requireReadyIfEnabled(Connector entity) {
        if (!Integer.valueOf(1).equals(entity.getEnabled())) return;
        String missing = missingRequirement(entity);
        if (missing != null) {
            throw new IllegalArgumentException(entity.getName() + "启用前必须补全连接配置：" + missing);
        }
    }

    /** 合并库中已有值与本次更新值（用于启用前的就绪校验）。 */
    private Connector merge(Connector stored, Connector updated) {
        Connector merged = new Connector();
        merged.setCode(stored.getCode());
        merged.setName(StringUtils.hasText(updated.getName()) ? updated.getName() : stored.getName());
        merged.setAuthType(StringUtils.hasText(updated.getAuthType()) ? updated.getAuthType() : stored.getAuthType());
        merged.setBaseUrl(StringUtils.hasText(updated.getBaseUrl()) ? updated.getBaseUrl() : stored.getBaseUrl());
        merged.setMcpUrl(updated.getMcpUrl() != null ? updated.getMcpUrl() : stored.getMcpUrl());
        merged.setEncryptedUsername(StringUtils.hasText(updated.getEncryptedUsername())
                ? updated.getEncryptedUsername() : stored.getEncryptedUsername());
        merged.setEncryptedPassword(StringUtils.hasText(updated.getEncryptedPassword())
                ? updated.getEncryptedPassword() : stored.getEncryptedPassword());
        merged.setEncryptedToken(StringUtils.hasText(updated.getEncryptedToken())
                ? updated.getEncryptedToken() : stored.getEncryptedToken());
        merged.setExtraConfig(updated.getExtraConfig() != null ? updated.getExtraConfig() : stored.getExtraConfig());
        merged.setEnabled(updated.getEnabled() != null ? updated.getEnabled() : stored.getEnabled());
        return merged;
    }

    private String mergeExtra(Connector stored, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(extraMap(stored));
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            if (key == null || !EXTRA_KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("扩展参数键不合法：" + key);
            }
            Object value = entry.getValue();
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                merged.remove(key);
                continue;
            }
            if (value instanceof Map || value instanceof List) {
                throw new IllegalArgumentException("扩展参数只支持字符串/数字/布尔：" + key);
            }
            merged.put(key, value);
        }
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            throw new IllegalArgumentException("扩展参数序列化失败");
        }
    }

    private void testBasic(Connector entity) {
        String username = credential(entity, "username");
        String password = credential(entity, "password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("请先配置服务账号与密码");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        postJsonAccept2xx(entity, body);
    }

    /**
     * 致远 OA 探活：先 REST（POST /seeyon/rest/token {userName,password} → {id}）；
     * REST 被网关拦截时降级网页表单登录（main.do?method=login）验证账号可用性
     * —— vReport 采集与 RPA 方案均走这条网页通道。
     */
    private String testSeeyon(Connector entity) {
        String username = credential(entity, "username");
        String password = credential(entity, "password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalStateException("请先配置 OA 账号与密码");
        }
        try {
            testSeeyonRest(entity, username, password);
            return "连接成功";
        } catch (IllegalStateException e) {
            // REST 不通（网关拦截/服务未开通）→ 降级网页表单登录验证账号
            String restError = e.getMessage();
            try {
                testSeeyonWebLogin(entity, username, password);
                return "连接成功（网页会话通道；REST 接口待 OA 管理员放行后启用）";
            } catch (IllegalStateException webError) {
                throw new IllegalStateException(webError.getMessage() + "；" + restError);
            }
        }
    }

    private void testSeeyonRest(Connector entity, String username, String password) {
        // A8 V8+/V9：token 为 GET query 形态；旧版为 POST JSON。先 query，失败再 POST。
        String token = seeyonTokenViaQuery(entity, username, password);
        if (StringUtils.hasText(token)) return;
        seeyonTokenViaPost(entity, username, password);
    }

    /** GET /seeyon/rest/token?userName=&password=（V8+/V9 形态）；失败返回 null 交由 POST 判定。 */
    private String seeyonTokenViaQuery(Connector entity, String username, String password) {
        try {
            String query = "userName=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                    + "&password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(entity.getBaseUrl()) + "/seeyon/rest/token?" + query))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() != 200 || text.startsWith("<") || text.isBlank()) return null;
            JsonNode root = objectMapper.readTree(text);
            String token = root.path("id").asText(root.path("token").asText(""));
            return StringUtils.hasText(token) ? token : null;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** POST /seeyon/rest/token（JSON 体，旧版形态）；失败按其错误抛出。 */
    private void seeyonTokenViaPost(Connector entity, String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", username);
        body.put("password", password);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(entity.getBaseUrl()) + "/seeyon/rest/token"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String text = response.body() == null ? "" : response.body();
            if (response.statusCode() == 401 && text.contains("<html")) {
                throw new IllegalStateException(entity.getName()
                        + " 网关拒绝访问(401)：REST 接口被前置网关拦截，请联系 OA 管理员将本系统 IP 加入白名单");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403
                    || response.statusCode() == 400 || response.statusCode() == 422) {
                throw new IllegalStateException(entity.getName() + "认证失败，请检查账号密码");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + "暂时不可用(" + response.statusCode() + ")");
            }
            JsonNode root = objectMapper.readTree(text.startsWith("<") ? "{}" : text);
            String token = root.path("id").asText(root.path("token").asText(""));
            if (!StringUtils.hasText(token)) {
                throw new IllegalStateException(entity.getName() + "认证失败，请检查账号密码");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "暂时不可用，请稍后重试");
        }
    }

    /** 网页表单登录探活：POST /seeyon/main.do?method=login（form）；成功 = 拿到 JSESSIONID 且无 loginerror。 */
    private void testSeeyonWebLogin(Connector entity, String username, String password) {
        try {
            String form = "login_username=" + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8)
                    + "&login_password=" + java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(entity.getBaseUrl()) + "/seeyon/main.do?method=login"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean loginError = response.headers().allValues("loginerror").stream().anyMatch("1"::equals);
            boolean hasSession = response.headers().allValues("Set-Cookie").stream()
                    .anyMatch(cookie -> cookie.contains("JSESSIONID"));
            if (loginError || !hasSession) {
                throw new IllegalStateException(entity.getName() + "网页登录失败，请检查 OA 账号密码（若正确，请确认账号未被停用）");
            }
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "网页登录请求失败，请稍后重试");
        }
    }

    private void testToken(Connector entity) {
        String token = credential(entity, "token");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("请先配置访问 Token");
        }
        getJson(entity, StringUtils.hasText(entity.getTestPath()) ? entity.getTestPath() : "/", token);
    }

    /** MCP 探活，返回成功文案；语雀在 MCP 网关受限（401/403）时降级 REST v2 验证 Token（官方 server 同链路）。 */
    private String testMcp(Connector entity) {
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
            if ((response.statusCode() == 401 || response.statusCode() == 403)
                    && CODE_YUQUE.equals(entity.getCode())) {
                // 语雀 MCP 网关 403：Token 有效但网关受限 → 降级 REST v2（官方 yuque-mcp-server 同一条链路）
                restUserProbe(entity);
                return "连接成功（REST v2 通道；语雀 MCP 网关受限，AI 查询与周报发布自动走 REST v2）";
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IllegalStateException(entity.getName() + " MCP 认证失败(403/401)：请检查 Token 与 MCP 开通情况");
            }
            if (response.statusCode() == 404) {
                throw new IllegalStateException(entity.getName() + " MCP 端点不存在(404)：请检查 MCP 服务地址");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(entity.getName() + " MCP 调用失败(" + response.statusCode() + ")");
            }
            if (!StringUtils.hasText(response.body())) {
                throw new IllegalStateException(entity.getName() + " MCP 响应为空");
            }
            return "连接成功";
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + " 服务暂时不可用，请稍后重试");
        }
    }

    private void postJsonAccept2xx(Connector entity, Map<String, Object> body) {
        try {
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
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(entity.getName() + "暂时不可用，请稍后重试");
        }
    }

    /** 语雀 REST v2 Token 校验（MCP 被网关拒绝时的降级验证）：先站点地址，再回退公共站点。 */
    private void restUserProbe(Connector entity) {
        String token = credential(entity, "token");
        List<String> bases = new ArrayList<>();
        if (StringUtils.hasText(entity.getBaseUrl())) bases.add(trimSlash(entity.getBaseUrl()));
        if (!bases.contains("https://www.yuque.com")) bases.add("https://www.yuque.com");
        IllegalStateException last = null;
        for (String base : bases) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/v2/user"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json")
                        .header("X-Auth-Token", token)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(response.body() == null ? "{}" : response.body());
                if (response.statusCode() == 200 && root.path("data").path("id").asLong(0) > 0) {
                    return;
                }
                last = new IllegalStateException(entity.getName() + " Token 无效或已过期");
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                last = new IllegalStateException(entity.getName() + " Token 校验请求失败");
            }
        }
        throw last == null ? new IllegalStateException(entity.getName() + " Token 无效或已过期") : last;
    }

    private void validate(ConnectorSaveRequest request) {
        if (!StringUtils.hasText(request.code()) || !CODE_PATTERN.matcher(request.code()).matches()) {
            throw new IllegalArgumentException("编码必须为 2-32 位小写字母/数字/下划线，且以字母开头");
        }
        String authType = normalizeAuth(request.authType());
        if (AUTH_MAIL.equals(authType)) return;
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new IllegalArgumentException("服务地址不能为空");
        }
        if (!request.baseUrl().startsWith("http://") && !request.baseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("服务地址必须是 HTTP(S) 地址");
        }
    }

    private String normalizeAuth(String authType) {
        if (!AUTH_TYPES.contains(authType)) {
            throw new IllegalArgumentException("认证类型必须是 BASIC / TOKEN / MCP / SEEYON / WECOM / MAIL");
        }
        return authType;
    }

    private void applyRequest(Connector entity, ConnectorSaveRequest request) {
        entity.setCode(request.code().trim());
        entity.setName(StringUtils.hasText(request.name()) ? request.name().trim() : request.code().trim());
        entity.setAuthType(normalizeAuth(request.authType()));
        entity.setBaseUrl(StringUtils.hasText(request.baseUrl()) ? trimSlash(request.baseUrl()) : "");
        entity.setMcpUrl(trimSlash(request.mcpUrl()));
        entity.setTestPath(StringUtils.hasText(request.testPath()) ? request.testPath().trim() : null);
        entity.setQueryPath(StringUtils.hasText(request.queryPath()) ? request.queryPath().trim() : null);
        entity.setReadPath(StringUtils.hasText(request.readPath()) ? request.readPath().trim() : null);
        if (request.extraConfig() != null) entity.setExtraConfig(mergeExtra(new Connector(), request.extraConfig()));
        if (StringUtils.hasText(request.username())) entity.setEncryptedUsername(cipher.encrypt(request.username()));
        if (StringUtils.hasText(request.password())) entity.setEncryptedPassword(cipher.encrypt(request.password()));
        if (StringUtils.hasText(request.token())) entity.setEncryptedToken(cipher.encrypt(request.token()));
        entity.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
    }

    private ConnectorView toView(Connector entity) {
        return new ConnectorView(entity.getId(), entity.getCode(), entity.getName(), entity.getAuthType(),
                entity.getBaseUrl(), entity.getMcpUrl(), entity.getTestPath(), entity.getQueryPath(),
                entity.getReadPath(), extraMap(entity),
                StringUtils.hasText(entity.getEncryptedUsername()),
                StringUtils.hasText(entity.getEncryptedPassword()),
                StringUtils.hasText(entity.getEncryptedToken()),
                Integer.valueOf(1).equals(entity.getEnabled()),
                "READY".equals(status(entity)), hint(entity),
                entity.getLastTestStatus(), entity.getLastTestMessage(), entity.getLastTestedAt(),
                Integer.valueOf(1).equals(entity.getBuiltIn()),
                entity.getSortOrder() == null ? 100 : entity.getSortOrder());
    }

    private Connector require(Long id) {
        Connector entity = mapper.selectById(id);
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
