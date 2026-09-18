package com.bu.management.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.config.SeeyonOaRuntimeConfig;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.mapper.SystemConfigItemMapper;
import com.bu.management.service.SeeyonOaConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * OA 网页会话通道（RPA 形态）：当致远 REST 接口被网关/策略拦截时的取数与审批通道。
 * 会话来源：管理员在连接器管理里粘贴浏览器 JSESSIONID（一次授权，会话复用）；
 * 采集走 ajax.do（sectionManager.doProjection 返回结构化 JSON），
 * 审批走事项详情页自发现的动作地址（collaboration.do），与页面同源。
 * 会话失效（__LOGOUT/登录页）时统一抛「需要重新授权」的可操作异常。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeeyonOaWebChannel {

    private static final String SESSION_ITEM_KEY = "oa.session-cookie";
    private static final String LOGIN_SHELL_MARK = "overflow_login";
    private static final String LOGOUT_MARK = "__LOGOUT";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SeeyonOaConfigService configService;
    private final SystemConfigItemMapper configItemMapper;
    private final EmailCredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile String sessionCookieCache;

    /** 待办/已办事项（网页通道解析后的统一结构）。 */
    public record WebAffair(String affairId, String title, String sender, String receiveTime, String type, String linkUrl) {}

    /** 详情页自发现的审批动作（动作名 → 请求路径）。 */
    public record FlowAction(String name, String path) {}

    /** 授权状态（供连接器页与授权入口展示）。 */
    public record SessionStatus(boolean authorized, String hint) {}

    // ==================== 会话管理 ====================

    public SessionStatus sessionStatus() {
        String cookie = sessionCookie();
        if (!StringUtils.hasText(cookie)) {
            return new SessionStatus(false, "未授权：请在连接器管理 → OA（致远）完成授权（自动登录 / 验证码 / 粘贴 JSESSIONID 均可）");
        }
        return new SessionStatus(true, "已授权（网页会话）");
    }

    /** 验证码挑战：挑战 ID + 验证码图片（base64 PNG）+ 过期时间戳。 */
    public record CaptchaChallenge(String challengeId, String imageBase64, long expireAt) {}

    private record Challenge(String cookies, long expireAt) {}

    /** 短期内存中的预登录会话（challengeId → cookies + 过期时间）。 */
    private final Map<String, Challenge> challenges = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000L;

    /** 拉取一张验证码（同时建立预登录会话），用于授权弹窗。 */
    public CaptchaChallenge captchaChallenge() {
        String preSession = fetchLoginPageSession();
        String id = java.util.UUID.randomUUID().toString();
        long expireAt = System.currentTimeMillis() + CHALLENGE_TTL_MS;
        // 取图请求可能轮换会话 Cookie（JSESSIONID/ts），必须并入挑战会话，否则登录时验证码校验不过
        HttpResponse<byte[]> imageResponse = getImageWithCookie(
                baseUrl() + "/seeyon/verifyCodeImage.jpg?v=" + System.currentTimeMillis(), preSession);
        String challengeSession = mergeCookies(preSession, cookiesFrom(imageResponse));
        challenges.put(id, new Challenge(challengeSession, expireAt));
        return new CaptchaChallenge(id,
                java.util.Base64.getEncoder().encodeToString(imageResponse.body()), expireAt);
    }

    /** 账号密码表单登录（可选验证码）；登录成功后立即用最小调用验证会话，并持久化。 */
    public SessionStatus loginWithPassword(String captchaChallengeId, String captcha) {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured() || !StringUtils.hasText(config.username()) || !StringUtils.hasText(config.password())) {
            throw new IllegalStateException("OA 集成未配置账号密码，请先在连接器管理补全");
        }
        String cookies = captchaChallengeId == null ? null : challengeCookies(captchaChallengeId);
        if (cookies == null && captchaChallengeId != null) {
            throw new IllegalStateException("验证码已过期，请重新获取");
        }
        String body = loginFormBody(config.username(), config.password(), captcha);
        String session = formLogin(cookies, body);
        if (session == null) {
            throw new IllegalStateException("登录失败：账号密码错误或验证码错误（错误码见 OA 返回）");
        }
        sessionCookieCache = session;
        try {
            ajaxAction("sectionManager", "doProjection",
                    Map.of("sectionBeanId", "pendingSection", "spaceType", "personal"));
        } catch (IllegalStateException e) {
            sessionCookieCache = null;
            throw new IllegalStateException("登录成功但会话不可用，请改用粘贴 JSESSIONID 方式授权");
        }
        persistSessionCookie(session);
        return new SessionStatus(true, "授权成功，OA 网页会话已生效");
    }

    /** 自动登录：不交互尝试一次表单登录（OA 不强制验证码时直接成功）。 */
    public SessionStatus tryAutoLogin() {
        try {
            return loginWithPassword(null, null);
        } catch (IllegalStateException e) {
            String message = e.getMessage();
            if (message != null && message.contains("验证码")) {
                return new SessionStatus(false, "OA 需要验证码登录，请在授权弹窗输入验证码（或粘贴 JSESSIONID）");
            }
            return new SessionStatus(false, "自动登录失败：" + message + "；可在授权弹窗输验证码或粘贴 JSESSIONID");
        }
    }

    private String challengeCookies(String challengeId) {
        Challenge challenge = challenges.remove(challengeId);
        if (challenge == null || System.currentTimeMillis() > challenge.expireAt()) return null;
        return challenge.cookies();
    }

    private String fetchLoginPageSession() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/seeyon/main.do?method=index"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "Java-http-client/17.0.7")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return cookiesFrom(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                last = new IllegalStateException("OA 登录页请求被中断");
            } catch (Exception e) {
                last = new IllegalStateException("OA 登录页不可达，请检查服务地址");
            }
            try {
                Thread.sleep(300L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last == null ? new IllegalStateException("OA 登录页不可达，请检查服务地址") : last;
    }

    /** 会话保活（由定时任务每 10 分钟调用）：有会话就 ping 一下，失效则清除并等待人工重新授权。 */
    public void keepAlive() {
        String cookie = sessionCookie();
        if (!StringUtils.hasText(cookie)) return;
        try {
            ajaxAction("sectionManager", "doProjection",
                    Map.of("sectionBeanId", "pendingSection", "spaceType", "personal"));
        } catch (IllegalStateException e) {
            log.warn("OA 会话保活失败，会话已失效：{}", e.getMessage());
            sessionCookieCache = null;
            persistSessionCookie(null);
        }
    }
    private String cookiesFrom(HttpResponse<?> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        List<String> cookies = new ArrayList<>();
        for (String setCookie : setCookies) {
            int semi = setCookie.indexOf(';');
            cookies.add(semi > 0 ? setCookie.substring(0, semi) : setCookie);
        }
        return String.join("; ", cookies);
    }

    private HttpResponse<byte[]> getImageWithCookie(String url, String cookie) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Java-http-client/17.0.7")
                    .header("Cookie", cookie)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null) {
                throw new IllegalStateException("验证码获取失败");
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("验证码获取失败，请稍后重试");
        }
    }

    /**
     * 表单登录：302 + JSESSIONID + 无 LoginError 即成功。
     * 失败时抛出带错误码的异常（LoginError 值透传给管理员，便于定位：验证码/账号/锁定）。
     */
    private String formLogin(String existingCookies, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/seeyon/main.do?method=login"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Java-http-client/17.0.7")
                    .header("Referer", baseUrl() + "/seeyon/main.do?method=index")
                    .header("Origin", baseUrl())
                    .header("Cookie", existingCookies == null ? "" : existingCookies)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String loginError = firstHeader(response, "LoginError", "loginerror");
            if (loginError != null) {
                throw new IllegalStateException(
                        "OA 登录失败（loginerror=" + loginError + "）：" + loginErrorHint(loginError));
            }
            String merged = mergeCookies(existingCookies, cookiesFrom(response));
            if (!StringUtils.hasText(merged) || !merged.contains("JSESSIONID")) return null;
            return merged;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** A8 V9 网页登录表单：login_password 才是真实密码字段；login_password1 是确认密码，漏传会 loginerror=11。 */
    private String loginFormBody(String username, String password, String captcha) {
        String body = "login_username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&login_password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&login_validatePwdStrength=1"
                + "&loginType=usernamePwd";
        if (StringUtils.hasText(captcha)) {
            body += "&login.VerifyCode=" + URLEncoder.encode(captcha.trim(), StandardCharsets.UTF_8);
        }
        return body;
    }

    private String loginErrorHint(String loginError) {
        return switch (loginError == null ? "" : loginError.trim()) {
            case "1" -> "账号或密码错误";
            case "9" -> "验证码错误，请刷新后重试";
            case "11" -> "登录参数不完整（账号/密码字段未按网页表单提交）";
            default -> "登录失败";
        };
    }

    private String firstHeader(HttpResponse<?> response, String... names) {
        for (String name : names) {
            String value = response.headers().firstValue(name).orElse(null);
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private String mergeCookies(String existing, String fresh) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String part : List.of(existing == null ? "" : existing, fresh == null ? "" : fresh)) {
            for (String pair : part.split(";")) {
                String trimmed = pair.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) merged.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /** 保存管理员粘贴的 JSESSIONID（校验后持久化到连接器扩展参数）。 */
    public SessionStatus authorize(String rawCookie) {
        String cookie = normalizeCookie(rawCookie);
        if (!StringUtils.hasText(cookie)) {
            throw new IllegalArgumentException("JSESSIONID 不能为空");
        }
        sessionCookieCache = cookie;
        try {
            // 用一次最小调用校验会话有效
            JsonNode ping = ajaxAction("sectionManager", "doProjection",
                    Map.of("sectionBeanId", "pendingSection", "spaceType", "personal"));
            if (ping == null) throw new IllegalStateException("会话无效");
        } catch (IllegalStateException e) {
            sessionCookieCache = null;
            throw new IllegalStateException("授权失败：JSESSIONID 无效或已过期，请在浏览器重新登录 OA 后复制最新值");
        }
        persistSessionCookie(cookie);
        return new SessionStatus(true, "授权成功，OA 网页会话已生效");
    }

    public void clearSession() {
        sessionCookieCache = null;
        persistSessionCookie(null);
    }

    /** 校验 Cookie 输入：支持粘贴完整 Cookie 串或仅 JSESSIONID 值。 */
    private String normalizeCookie(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        if (text.contains("JSESSIONID")) {
            return text;
        }
        return "JSESSIONID=" + text;
    }

    private String sessionCookie() {
        if (StringUtils.hasText(sessionCookieCache)) return sessionCookieCache;
        sessionCookieCache = readPersistedCookie();
        return sessionCookieCache;
    }

    /** 会话 Cookie 持久化在隐藏的敏感配置项里（connector-hub/oa.session-cookie，AES 加密，不出现在配置管理页）。 */
    private String readPersistedCookie() {
        SystemConfigItem item = findSessionItem();
        if (item == null || !StringUtils.hasText(item.getConfigValue())) return null;
        try {
            return cipher.decrypt(item.getConfigValue());
        } catch (IllegalStateException e) {
            log.warn("OA 会话凭据解密失败，请重新授权");
            return null;
        }
    }

    private void persistSessionCookie(String cookie) {
        SystemConfigItem item = findSessionItem();
        if (item == null) {
            item = new SystemConfigItem();
            item.setGroupCode("connector-hub");
            item.setGroupName("连接器收口");
            item.setGroupDescription("历史连接配置搬迁标记（内部使用，不展示）");
            item.setConfigKey(SESSION_ITEM_KEY);
            item.setConfigName("OA 网页会话凭据");
            item.setConfigDescription("管理员授权粘贴的 JSESSIONID（AES 加密，内部使用，不展示）");
            item.setValueType("PASSWORD");
            item.setIsSensitive(1);
            item.setIsRequired(0);
            item.setSortOrder(90);
            item.setStatus(0);
            item.setConfigValue(cookie == null ? null : cipher.encrypt(cookie));
            configItemMapper.insert(item);
            return;
        }
        SystemConfigItem patch = new SystemConfigItem();
        patch.setId(item.getId());
        patch.setConfigValue(cookie == null ? null : cipher.encrypt(cookie));
        configItemMapper.updateById(patch);
    }

    private SystemConfigItem findSessionItem() {
        return configItemMapper.selectOne(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, "connector-hub")
                .eq(SystemConfigItem::getConfigKey, SESSION_ITEM_KEY)
                .last("LIMIT 1"));
    }

    // ==================== 网页通道调用 ====================

    /** 带会话的 GET（JSON），检出 __LOGOUT / 登录页时判定会话失效。 */
    public JsonNode ajaxAction(String managerName, String managerMethod, Map<String, Object> arguments) {
        String argumentsJson;
        try {
            argumentsJson = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (Exception e) {
            throw new IllegalStateException("OA 调用参数序列化失败");
        }
        String url = baseUrl() + "/seeyon/ajax.do?method=ajaxAction&managerName=" + managerName
                + "&managerMethod=" + managerMethod
                + "&arguments=" + URLEncoder.encode(argumentsJson, StandardCharsets.UTF_8);
        String body = getWithSession(url);
        if (body == null) return null;
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("OA 网页通道返回格式异常(" + managerName + ")");
        }
    }

    /** 带会话的原始抓取（用于详情页 HTML 与 vReport）。 */
    public byte[] fetchBytes(String url) {
        String fullUrl = url.startsWith("http") ? url : baseUrl() + url;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", requireSession())
                .header("User-Agent", "Java-http-client/17.0.7")
                .header("X-Requested-With", "XMLHttpRequest")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (response.statusCode() != 200 || body == null || isLoggedOut(body)) {
                invalidateAndFail();
            }
            return body;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("OA 网页通道请求失败，请稍后重试");
        }
    }

    // ==================== 待办 / 已办 ====================

    /**
     * 待办事项（网页通道）：sectionManager.doProjection → Data.rows → 统一结构。
     * 与门户「全部待办」面板同一数据源（已验证结构）。
     */
    public List<WebAffair> listPendingAffairs() {
        return listAffairs(true);
    }

    public List<WebAffair> listDoneAffairs() {
        return listAffairs(false);
    }

    private List<WebAffair> listAffairs(boolean pending) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sectionBeanId", pending ? "pendingSection" : "doneSection");
        args.put("spaceType", "personal");
        JsonNode response = ajaxAction("sectionManager", "doProjection", args);
        List<WebAffair> affairs = new ArrayList<>();
        JsonNode rows = response == null ? null : response.path("Data").path("rows");
        if (rows == null || !rows.isArray()) return affairs;
        for (JsonNode row : rows) {
            WebAffair affair = parseRow(row);
            if (affair != null) affairs.add(affair);
        }
        return affairs;
    }

    private WebAffair parseRow(JsonNode row) {
        JsonNode cells = row.path("cells");
        if (!cells.isArray() || cells.isEmpty()) return null;
        String title = null, sender = null, time = null, type = null, link = null, affairId = null;
        for (JsonNode cell : cells) {
            String text = cell.path("cellContentHTML").asText(cell.path("cellContent").asText(null));
            String cellLink = cell.path("linkURL").asText(null);
            if (StringUtils.hasText(cellLink) && StringUtils.hasText(text)) {
                title = text;
                link = cellLink;
                affairId = extractParam(cellLink, "affairId");
                continue;
            }
            if (title == null) continue;
            if (sender == null && cell.has("handler")) {
                sender = text;
                continue;
            }
            if (sender != null && time == null && text != null && text.matches(".*\\d.*")) {
                time = text;
                continue;
            }
            if (time != null && type == null) type = text;
        }
        if (!StringUtils.hasText(title)) return null;
        return new WebAffair(affairId, title, sender, time, type, link);
    }

    private String extractParam(String url, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ==================== 审批动作（自发现 + 执行） ====================

    /**
     * 详情页自发现审批动作：抓 collaboration summary 页面，
     * 解析含 affairId 的动作地址（同意/不同意/提交等按钮）。
     */
    public List<FlowAction> discoverFlowActions(String affairId) {
        String summaryPath = "/seeyon/collaboration/collaboration.do?method=summary"
                + "&openFrom=listPending&affairId=" + affairId + "&showTab=true";
        byte[] body = fetchBytes(summaryPath);
        String html = new String(body, StandardCharsets.UTF_8);
        List<FlowAction> actions = new ArrayList<>();
        // 动作按钮的 URL 形态：collaboration.do?method=<action>&...affairId=...
        Matcher matcher = Pattern.compile(
                "(?:href|url|action)[\"'=:\\s]+([\"']?)(/seeyon/collaboration/[a-zA-Z.]+\\?method=[a-zA-Z]+[^\"'\\s]*affairId=" + affairId + "[^\"'\\s]*)\\1")
                .matcher(html);
        while (matcher.find()) {
            String path = matcher.group(2);
            String name = extractParam(path, "attitude") != null ? extractParam(path, "attitude")
                    : extractParam(path, "method");
            actions.add(new FlowAction(name == null ? "action" : name, path));
        }
        return actions;
    }

    /**
     * 执行审批动作：取详情页自发现的动作地址后发起 POST。
     * action 支持中文别名：同意/不同意/提交。
     */
    public String actOnAffair(String affairId, String action) {
        List<FlowAction> actions = discoverFlowActions(affairId);
        if (actions.isEmpty()) {
            throw new IllegalStateException("事项详情页未解析到可用审批动作（可能已处理或无权处理）");
        }
        String wanted = switch (action == null ? "" : action) {
            case "approve", "同意" -> "同意";
            case "reject", "不同意" -> "不同意";
            default -> action;
        };
        FlowAction target = actions.stream()
                .filter(a -> a.name() != null && a.name().contains(wanted))
                .findFirst()
                .orElse(actions.get(0));
        byte[] body = postWithSession(target.path(), "affairId=" + affairId);
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.contains("成功") || text.contains("success") || text.isBlank()) {
            return "已执行：" + wanted;
        }
        return "已提交：" + wanted + "（" + text.substring(0, Math.min(120, text.length())) + "）";
    }

    private byte[] postWithSession(String path, String formBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(path.startsWith("http") ? path : baseUrl() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", requireSession())
                .header("User-Agent", "Java-http-client/17.0.7")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", baseUrl() + "/seeyon/main.do?method=main")
                .POST(HttpRequest.BodyPublishers.ofString(formBody == null ? "" : formBody))
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body != null && isLoggedOut(body)) {
                invalidateAndFail();
            }
            return body == null ? new byte[0] : body;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("OA 审批请求失败，请稍后重试");
        }
    }

    // ==================== 内部 ====================

    private String baseUrl() {
        SeeyonOaRuntimeConfig config = configService.getRuntimeConfig();
        if (!config.isConfigured()) {
            throw new IllegalStateException("OA 集成尚未完成配置");
        }
        return config.effectiveBaseUrl();
    }

    private String requireSession() {
        String cookie = sessionCookie();
        if (!StringUtils.hasText(cookie)) {
            throw new IllegalStateException("OA 会话未授权：请在连接器管理 → OA（致远）粘贴 JSESSIONID 完成授权");
        }
        return cookie;
    }

    private boolean isLoggedOut(byte[] body) {
        if (body == null || body.length == 0) return false;
        String text = new String(body, 0, Math.min(body.length, 2048), StandardCharsets.UTF_8);
        if (body.length < 64 && text.contains(LOGOUT_MARK)) return true;
        return text.contains(LOGIN_SHELL_MARK);
    }

    private void invalidateAndFail() {
        throw new IllegalStateException("OA 会话已失效，请在连接器管理 → OA（致远）重新粘贴 JSESSIONID 授权");
    }

    private String getWithSession(String url) {
        byte[] body = fetchBytes(url);
        return new String(body, StandardCharsets.UTF_8);
    }
}
