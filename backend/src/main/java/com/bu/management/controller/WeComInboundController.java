package com.bu.management.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import com.bu.management.service.ProjectFactService;
import com.bu.management.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企微内部群入站端点：群机器人 / 转发脚本把**内部群**里的发言丢进项目事实层。
 *
 * <p>这是<b>内部群机器人/转发脚本入口</b>，不采集员工个人微信，也不采外部联系人单聊：
 * <ul>
 *   <li>只接受本公司内部群（企微自建群）的发言转发；</li>
 *   <li>{@code external == true}（外部联系人单聊）一律 400 拒绝，绝不落库 —— 那部分属于
 *       「会话内容存档」范围，需企业微信管理员开通授权，见 docs/wecom-archive-plan.md；</li>
 *   <li>不做屏幕/键盘/剪贴板采集，只处理被显式转发过来的文本。</li>
 * </ul>
 *
 * <p>鉴权与 {@link ProjectContextInternalController#tokenOk} 同口径：SecurityConfig 放行该前缀，
 * 靠 {@code X-Sidecar-Token} 常量时间比对防护；令牌未配置（开发环境）直接放行。
 *
 * <p>写入路径与将来会话存档的落库路径完全一致（CHAT_EXPORT + appendManual + wecom: 前缀幂等键），
 * 所以没有存档授权时「转发脚本 + 本端点」即可跑通全链路。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeComInboundController {

    /** 单条发言幂等键前缀；source_ref = wecom:{sha256} */
    private static final String SOURCE_REF_PREFIX = "wecom:";
    /** 未知成员的正文前缀，让读事实的人一眼看出这条没对上花名册 */
    private static final String UNKNOWN_ACTOR_PREFIX = "[未知成员] ";
    private static final String FALLBACK_TITLE = "企微群消息";
    private static final int MAX_TITLE = 300;

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private final ProjectFactService factService;
    private final UserMapper userMapper;
    private final ProjectContextProperties properties;

    /**
     * 企微群消息转发体。
     *
     * @param sender     发送人显示名（企微昵称/花名）
     * @param senderKey  发送人企微 userid（有则优先用它匹配花名册）
     * @param chatName   群名称
     * @param time       发言时间，"yyyy-MM-dd HH:mm" 或 ISO；解析失败用服务端 now()
     * @param text       发言正文，空则 400
     * @param visibility PROJECT / PRIVATE_COMPANY / OWNER，缺省 PROJECT
     * @param external   是否外部联系人单聊；true 直接拒绝
     */
    public record WeComHookRequest(String sender, String senderKey, String chatName, String time,
                                   String text, String visibility, Boolean external) {
    }

    @PostMapping("/internal/project-context/{projectId}/wecom-hook")
    public ResponseEntity<?> hook(@PathVariable Long projectId,
            @RequestBody WeComHookRequest body,
            HttpServletRequest request) {
        if (!tokenOk(request)) {
            return ResponseEntity.status(401).body(Result.error(401, "invalid token"));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Result.error(400, "请求体不能为空"));
        }
        if (Boolean.TRUE.equals(body.external())) {
            return ResponseEntity.badRequest().body(Result.error(400,
                    "外部联系人单聊不走本端点：需企业管理员开通会话内容存档（msgaudit）授权"));
        }
        String text = strip(body.text());
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Result.error(400, "text 不能为空"));
        }
        String sender = strip(body.sender());
        String senderKey = strip(body.senderKey());
        String chatName = strip(body.chatName());
        String rawTime = strip(body.time());

        Long actorId = resolveActorId(senderKey, sender);
        LocalDateTime occurredAt = parseTime(rawTime);

        String sourceRef = SOURCE_REF_PREFIX + sha256Hex(String.join("|",
                String.valueOf(projectId), chatName, sender, rawTime, text));
        String title = truncate(buildTitle(chatName, sender));
        String bodyText = buildBody(rawTime, sender, text, actorId == null);

        factService.appendManual(projectId, ProjectFactService.SOURCE_CHAT_EXPORT, sourceRef, actorId,
                occurredAt, title, bodyText, body.visibility());
        ProjectFact fact = factService.find(ProjectFactService.SOURCE_CHAT_EXPORT, sourceRef);
        return ResponseEntity.ok(Result.success(fact));
    }

    /** senderKey（企微 userid）优先，其次 sender 显示名；都匹配不上返回 null。 */
    private Long resolveActorId(String senderKey, String sender) {
        for (String key : new String[] {senderKey, sender}) {
            if (key.isEmpty()) {
                continue;
            }
            Long id = lookupUser(key);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** 按 username / realName 查用户，忽略大小写；同名取第一条。 */
    private Long lookupUser(String key) {
        List<User> users;
        try {
            users = userMapper.selectList(new LambdaQueryWrapper<User>()
                    .and(w -> w.eq(User::getUsername, key).or().eq(User::getRealName, key))
                    .last("limit 5"));
        } catch (RuntimeException e) {
            log.warn("wecom-hook 花名册查询失败，按未知成员处理: key={} err={}", key, e.getMessage());
            return null;
        }
        if (users == null || users.isEmpty()) {
            return null;
        }
        for (User user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            if (key.equalsIgnoreCase(strip(user.getUsername())) || key.equalsIgnoreCase(strip(user.getRealName()))) {
                return user.getId();
            }
        }
        return users.get(0) == null ? null : users.get(0).getId();
    }

    /** "yyyy-MM-dd HH:mm" / ISO / 日期各种写法；解析不出来用 now()。 */
    private static LocalDateTime parseTime(String raw) {
        if (raw.isEmpty()) {
            return LocalDateTime.now();
        }
        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // 换下一种格式
            }
        }
        try {
            return LocalDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // 继续尝试带时区的 ISO
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期
        }
        try {
            return LocalDate.parse(raw).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            log.debug("wecom-hook 时间解析失败，用 now(): {}", raw);
            return LocalDateTime.now();
        }
    }

    private static String buildTitle(String chatName, String sender) {
        List<String> parts = new ArrayList<>(2);
        if (!chatName.isEmpty()) {
            parts.add(chatName);
        }
        if (!sender.isEmpty()) {
            parts.add(sender);
        }
        return parts.isEmpty() ? FALLBACK_TITLE : String.join(" · ", parts);
    }

    /** body = "time sender: text"，空字段跳过；动作人是 null 时正文前缀 [未知成员]。 */
    private static String buildBody(String rawTime, String sender, String text, boolean unknownActor) {
        StringBuilder sb = new StringBuilder();
        if (!rawTime.isEmpty()) {
            sb.append(rawTime).append(' ');
        }
        if (unknownActor) {
            sb.append(UNKNOWN_ACTOR_PREFIX);
        }
        sb.append(sender.isEmpty() ? "未知发言人" : sender).append(": ").append(text);
        return sb.toString();
    }

    private static String strip(String value) {
        return StringUtils.hasText(value) ? value.strip() : "";
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_TITLE ? value : value.substring(0, MAX_TITLE);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha256.digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 未配置令牌（开发环境）直接放行；配置后做常量时间比对。 */
    private boolean tokenOk(HttpServletRequest request) {
        String expected = properties.getToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        String actual = request.getHeader("X-Sidecar-Token");
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
