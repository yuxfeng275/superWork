package com.bu.management.integration;

import com.bu.management.entity.Connector;
import com.bu.management.service.ConnectorRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 企业微信官方 CLI（wecom-cli）机器人通道客户端。
 *
 * <p>与 {@link WeComClient}（自建应用通道：corpId/agentId/应用 Secret，用于应用消息推送）是两条独立授权域：
 * 本通道走智能机器人（Bot ID + Bot Secret），提供待办、会议（含智能纪要/转写）、文档、表格、微盘、邮件、日程、通讯录等业务能力。
 *
 * <p>授权一次性完成（Bot 凭证或扫码），access token 由 CLI 静默续期；但**品类授权**需机器人在企微侧逐项授权且会过期，
 * 调用返回 850002/850003/851008 时归类为「品类未授权」，并把 CLI 的 help_message（含续期链接）原样带出。
 *
 * <p>二进制为静态链接可执行文件（镜像内固定版本），无需 Node 运行时；凭据落在 {@code WECOM_CLI_CONFIG_DIR} 卷内。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeComCliClient {

    /** 品类未授权类错误码：850002 未授权 / 850003 授权已过期 / 851008 部分未授权。 */
    private static final List<Integer> AUTH_ERRCODES = List.of(850002, 850003, 851008);

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration AUTH_INIT_TIMEOUT = Duration.ofSeconds(90);

    private final ConnectorRegistryService registryService;
    private final ObjectMapper objectMapper;

    @Value("${wecom.cli.bin:/app/wecom-cli/wecom-cli}")
    private String cliBin;

    @Value("${wecom.cli.config-dir:}")
    private String configDirOverride;

    /** CLI 工作目录（cwd 与下载落盘处），必须独立于配置目录。 */
    @Value("${wecom.cli.workspace:}")
    private String workspaceOverride;

    /** 授权状态（连接器页展示）。 */
    public record CliStatus(boolean authorized, String botId, String hint) {}

    /** 品类可用性。 */
    public record Capability(String service, String label, String state, String message) {}

    /** 命令执行结果；errcode=0 表示成功。 */
    public record CliResult(int errcode, String errmsg, JsonNode payload, String helpMessage, boolean authFailure) {
        public boolean success() {
            return errcode == 0;
        }
    }

    /** 授权方式。 */
    public enum BindSource {
        /** 连接器内的 Bot ID + Secret。 */
        CONNECTOR,
        /** 扫码（返回二维码，人工确认）。 */
        QRCODE
    }

    // ==================== 路径与凭据 ====================

    /** 配置目录：容器内为 /data/wecom-cli（卷），本地开发回退到用户目录。 */
    public String configDir() {
        if (StringUtils.hasText(configDirOverride)) return configDirOverride;
        String env = System.getenv("WECOM_CLI_CONFIG_DIR");
        if (StringUtils.hasText(env)) return env;
        return Path.of(System.getProperty("user.home", "/tmp"), ".config", "wecom").toString();
    }

    /**
     * CLI 工作目录：CLI 1.3.0 起文件读写限「进程 cwd + 系统临时目录」，且**配置目录一律拒绝**
     * （含其子路径），故工作目录必须独立于 {@link #configDir()}。
     */
    private Path workspaceDir() {
        String configured = StringUtils.hasText(workspaceOverride)
                ? workspaceOverride : Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "wecom-cli").toString();
        Path workspace = Path.of(configured);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 wecom-cli 工作目录：" + workspace + "（" + e.getMessage() + "）");
        }
        return workspace;
    }

    private boolean binaryPresent() {
        return Files.isExecutable(Path.of(cliBin));
    }

    private String configuredBotId() {
        return registryService.findByCode(ConnectorRegistryService.CODE_WECOM)
                .map(entity -> registryService.extra(entity, "botId"))
                .orElse(null);
    }

    private String configuredBotSecret() {
        return registryService.findByCode(ConnectorRegistryService.CODE_WECOM)
                .map(entity -> registryService.credential(entity, "botSecret"))
                .orElse(null);
    }

    // ==================== 状态 ====================

    /** 授权状态：CLI 是否可用 + 是否已授权 + Bot ID。 */
    public CliStatus status() {
        if (!binaryPresent()) {
            return new CliStatus(false, null, "wecom-cli 未安装（镜像未内置或路径不正确：" + cliBin + "）");
        }
        ProcessResult result = run(List.of("auth", "show"), STATUS_TIMEOUT);
        if (result.exitCode() != 0) {
            return new CliStatus(false, null, "无法读取授权状态：" + trim(result.stderrOrStdout()));
        }
        String botId = null;
        for (String line : result.stdout().split("\n")) {
            if (line.startsWith("Bot ID:")) {
                botId = line.substring("Bot ID:".length()).trim();
            }
        }
        boolean authorized = result.stdout().contains("Status: authorized");
        if (!authorized) {
            return new CliStatus(false, botId, "机器人通道未授权：填写 Bot ID + Secret 后保存，或用扫码授权");
        }
        return new CliStatus(true, botId, "已授权（Bot " + (botId == null ? "-" : botId) + "）");
    }

    // ==================== 授权 ====================

    /**
     * 用 Bot 凭证完成授权（非 TTY 直连，无需人工）。
     * 校验失败时 CLI 以非 0 退出并输出结构化错误。
     */
    public CliStatus authorizeWithBotCredentials(String botId, String botSecret) {
        if (!StringUtils.hasText(botId) || !StringUtils.hasText(botSecret)) {
            throw new IllegalArgumentException("Bot ID 与 Bot Secret 均不能为空");
        }
        ProcessResult result = run(
                List.of("auth", "init", "--bot-id", botId, "--secret", botSecret), AUTH_INIT_TIMEOUT);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("机器人凭证校验失败：" + trim(result.stderrOrStdout()));
        }
        return status();
    }

    /** 发起扫码授权：返回二维码 PNG 路径与 base64（后台等待用户扫码，最长 5 分钟）。 */
    public QrSession startQrAuthorization() {
        if (!binaryPresent()) {
            throw new IllegalStateException("wecom-cli 未安装（" + cliBin + "）");
        }
        String sessionId = java.util.UUID.randomUUID().toString();
        Path qrFile = workspaceDir().resolve("qr-" + sessionId + ".png");
        ProcessBuilder builder = new ProcessBuilder(
                cliBin, "auth", "init", "--noninteractive", "--no-browser", "--output-qrcode", qrFile.toString());
        builder.directory(workspaceDir().toFile());
        builder.environment().put("WECOM_CLI_CONFIG_DIR", configDir());
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            // 先启动输出收集，再等待二维码文件：CLI 早期失败时才能带回错误原因
            Thread stdoutReader = drain(process.getInputStream(), stdout);
            Thread stderrReader = drain(process.getErrorStream(), stderr);
            stdoutReader.start();
            stderrReader.start();
            // 等待二维码文件出现（最多 15 秒），随后进程在后台继续等待扫码
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline && !Files.exists(qrFile)) {
                if (!process.isAlive()) break;
                Thread.sleep(200L);
            }
            if (!Files.exists(qrFile)) {
                process.destroyForcibly();
                String detail = stderr.length() > 0 ? stderr.toString() : stdout.toString();
                throw new IllegalStateException("二维码生成失败：" + (StringUtils.hasText(detail)
                        ? trim(detail) : "CLI 未输出二维码（退出码 " + (process.isAlive() ? "运行中" : process.exitValue()) + "）"));
            }
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(qrFile));
            QrSession session = new QrSession(sessionId, base64, System.currentTimeMillis() + 300_000L, process);
            qrSessions.put(sessionId, session);
            process.onExit().thenAccept(p -> session.exitCode = p.exitValue());
            return session;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动扫码授权：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("扫码授权被中断");
        }
    }

    /** 扫码会话（内存态，5 分钟过期）。 */
    public static final class QrSession {
        private final String sessionId;
        private final String imageBase64;
        private final long expireAt;
        private final Process process;
        private volatile Integer exitCode;

        QrSession(String sessionId, String imageBase64, long expireAt, Process process) {
            this.sessionId = sessionId;
            this.imageBase64 = imageBase64;
            this.expireAt = expireAt;
            this.process = process;
        }

        public String sessionId() {
            return sessionId;
        }

        public String imageBase64() {
            return imageBase64;
        }

        public long expireAt() {
            return expireAt;
        }
    }

    private final Map<String, QrSession> qrSessions = new java.util.concurrent.ConcurrentHashMap<>();

    /** 扫码进度：pending（等待扫码）/ authorized / expired / failed。 */
    public Map<String, Object> qrPoll(String sessionId) {
        QrSession session = qrSessions.get(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (session == null) {
            result.put("status", "expired");
            result.put("hint", "扫码会话不存在或已过期，请重新发起");
            return result;
        }
        if (System.currentTimeMillis() > session.expireAt) {
            session.process.destroyForcibly();
            qrSessions.remove(sessionId);
            result.put("status", "expired");
            result.put("hint", "二维码已过期（5 分钟），请重新发起");
            return result;
        }
        Integer exitCode = session.exitCode;
        if (exitCode == null) {
            result.put("status", "pending");
            return result;
        }
        qrSessions.remove(sessionId);
        CliStatus current = status();
        if (exitCode == 0 && current.authorized()) {
            result.put("status", "authorized");
            result.put("botId", current.botId());
            return result;
        }
        result.put("status", "failed");
        result.put("hint", current.hint());
        return result;
    }

    // ==================== 执行 ====================

    /** 执行一次 CLI 调用（service + 子路径 + 命名参数）。 */
    public CliResult exec(String service, List<String> pathAndArgs) {
        List<String> args = new ArrayList<>();
        args.add(service);
        args.addAll(pathAndArgs);
        return parse(run(args, EXEC_TIMEOUT));
    }

    /** 执行并断言成功；失败抛可操作异常（含品类授权引导）。 */
    public JsonNode execOrThrow(String service, List<String> pathAndArgs) {
        CliResult result = exec(service, pathAndArgs);
        if (!result.success()) {
            throw new IllegalStateException(describe(result));
        }
        return result.payload();
    }

    /** 失败原因描述：品类未授权时原样带出 CLI 的 help_message（官方要求逐字展示）。 */
    public String describe(CliResult result) {
        if (result.success()) return "调用成功";
        if (result.authFailure() && StringUtils.hasText(result.helpMessage())) {
            return unescape(result.helpMessage());
        }
        return "企业微信接口返回错误（errcode=" + result.errcode() + "）：" + result.errmsg();
    }

    private CliResult parse(ProcessResult result) {
        String stdout = result.stdout().trim();
        JsonNode node = null;
        if (StringUtils.hasText(stdout)) {
            try {
                node = objectMapper.readTree(stdout);
            } catch (Exception e) {
                log.debug("wecom-cli 输出非 JSON：{}", trim(stdout));
            }
        }
        if (node == null) {
            // CLI 自身错误（893xxx 段）以 {"error":{...}} 输出；此处兜底为通用失败
            String message = StringUtils.hasText(stdout) ? trim(stdout) : trim(result.stderrOrStdout());
            int code = result.exitCode() == 0 ? -1 : result.exitCode();
            return new CliResult(code, message, null, null, false);
        }
        if (node.has("error")) {
            JsonNode error = node.path("error");
            return new CliResult(error.path("code").asInt(-1), error.path("message").asText(""),
                    node, null, false);
        }
        int errcode = node.path("errcode").asInt(0);
        String help = node.path("help_message").asText(null);
        boolean authFailure = AUTH_ERRCODES.contains(errcode);
        return new CliResult(errcode, node.path("errmsg").asText(""), node, help, authFailure);
    }

    // ==================== 进程执行 ====================

    private record ProcessResult(int exitCode, String stdout, String stderr) {
        String stderrOrStdout() {
            return StringUtils.hasText(stderr) ? stderr : stdout;
        }
    }

    private ProcessResult run(List<String> args, Duration timeout) {
        if (!binaryPresent()) {
            throw new IllegalStateException("wecom-cli 未安装（" + cliBin + "）");
        }
        List<String> command = new ArrayList<>();
        command.add(cliBin);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceDir().toFile());
        builder.environment().put("WECOM_CLI_CONFIG_DIR", configDir());
        try {
            Process process = builder.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            Thread out = drain(process.getInputStream(), stdout);
            Thread err = drain(process.getErrorStream(), stderr);
            out.start();
            err.start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("企业微信命令超时（" + timeout.toSeconds() + " 秒）：" + String.join(" ", args.subList(1, args.size())));
            }
            out.join(5_000L);
            err.join(5_000L);
            return new ProcessResult(process.exitValue(), stdout.toString(), stderr.toString());
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 wecom-cli：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("企业微信命令被中断");
        }
    }

    private Thread drain(InputStream stream, StringBuilder sink) {
        return new Thread(() -> {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程结束时流关闭属正常
            }
        });
    }

    private String trim(String text) {
        if (text == null) return "";
        String cleaned = text.strip();
        return cleaned.length() > 400 ? cleaned.substring(0, 400) + "…" : cleaned;
    }

    /** CLI 的 help_message 里换行是字面 \n，展示前还原。 */
    private String unescape(String text) {
        return text.replace("\\n", "\n").replace("\\/", "/");
    }

    // ==================== 品类可用性 ====================

    /** 品类的探测命令（只读、最小代价）。 */
    private static final Map<String, List<String>> CAPABILITY_PROBES = new LinkedHashMap<>();
    private static final Map<String, String> CAPABILITY_LABELS = new LinkedHashMap<>();

    static {
        CAPABILITY_PROBES.put("contact", List.of("users", "search", "--keywords", "张"));
        CAPABILITY_PROBES.put("todo", List.of("list", "--limit", "1"));
        CAPABILITY_PROBES.put("meeting", List.of("list", "--begin-time", "2026-01-01 00:00:00", "--end-time", "2026-01-02 00:00:00", "--limit", "1"));
        CAPABILITY_PROBES.put("message", List.of("aibot", "sessions", "list"));
        CAPABILITY_PROBES.put("doc", List.of("search", "--keywords", "周报"));
        CAPABILITY_PROBES.put("calendar", List.of("schedules", "list", "--begin-time", "2026-01-01 00:00:00", "--end-time", "2026-01-02 00:00:00"));
        CAPABILITY_PROBES.put("disk", List.of("files", "list", "--limit", "1"));
        CAPABILITY_PROBES.put("mail", List.of("search"));
        CAPABILITY_LABELS.put("contact", "通讯录");
        CAPABILITY_LABELS.put("todo", "待办");
        CAPABILITY_LABELS.put("meeting", "会议（含纪要/转写）");
        CAPABILITY_LABELS.put("message", "消息推送");
        CAPABILITY_LABELS.put("doc", "文档");
        CAPABILITY_LABELS.put("calendar", "日程");
        CAPABILITY_LABELS.put("disk", "微盘");
        CAPABILITY_LABELS.put("mail", "邮件");
    }

    /**
     * 品类授权体检：逐项最小调用，判定 可用 / 未授权 / 已过期 / 不可用。
     * 结果按 10 分钟缓存，避免每次打开页面都打满企微接口。
     */
    private volatile List<Capability> capabilityCache;
    private volatile long capabilityCachedAt;

    public List<Capability> capabilities(boolean refresh) {
        if (!refresh && capabilityCache != null
                && System.currentTimeMillis() - capabilityCachedAt < 10 * 60 * 1000L) {
            return capabilityCache;
        }
        List<Capability> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : CAPABILITY_PROBES.entrySet()) {
            String service = entry.getKey();
            String label = CAPABILITY_LABELS.getOrDefault(service, service);
            try {
                CliResult probe = exec(service, entry.getValue());
                if (probe.success()) {
                    result.add(new Capability(service, label, "AVAILABLE", "可用"));
                } else if (probe.authFailure()) {
                    result.add(new Capability(service, label,
                            probe.errcode() == 850003 ? "EXPIRED" : "UNAUTHORIZED",
                            describe(probe)));
                } else {
                    result.add(new Capability(service, label, "ERROR",
                            "errcode=" + probe.errcode() + " " + probe.errmsg()));
                }
            } catch (Exception e) {
                result.add(new Capability(service, label, "ERROR", e.getMessage()));
            }
        }
        capabilityCache = result;
        capabilityCachedAt = System.currentTimeMillis();
        return result;
    }

    /** 授权状态变化后清空体检缓存。 */
    public void invalidateCapabilities() {
        capabilityCache = null;
        capabilityCachedAt = 0L;
    }

    /** 连接器实体（供上层读取 extra.botId 等）。 */
    public Connector connectorEntity() {
        return registryService.findByCode(ConnectorRegistryService.CODE_WECOM)
                .orElseThrow(() -> new IllegalStateException("未找到企业微信连接器"));
    }

    /** 已配置的 Bot ID（连接器 extra）。 */
    public String botId() {
        return configuredBotId();
    }

    /** 已配置的 Bot Secret（连接器加密列）。 */
    public String botSecret() {
        return configuredBotSecret();
    }
}
