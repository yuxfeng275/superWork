package com.bu.management.service;

import com.bu.management.entity.ProjectFact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 投递入口：成员把本机文件、补充说明、聊天记录灌进 project_fact。
 *
 * <p>三条通道都只做「归一 + 幂等写事实」，不改任何既有来源适配器：
 * <ul>
 *   <li>文件：落盘后 source_ref = file:{projectId}:{sha256}，同一文件重传只更新不新增；</li>
 *   <li>说明：source_ref = note:{uuid}，本来就没有天然幂等键，每次都是新事实；</li>
 *   <li>聊天记录：source_ref = chat:{projectId}:{sha256(正文)}，同一段记录重传不新增。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectFactIntakeService {

    private static final int MAX_TITLE = 300;
    /** 文本类文件正文抽取上限 */
    private static final int TEXT_BODY_CHARS = 20000;
    /** 超过该大小的文件不抽正文，body 只放 note */
    private static final long TEXT_BODY_MAX_BYTES = 256 * 1024;
    private static final int DEFAULT_LIST_LIMIT = 50;

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "csv", "json", "log", "yml", "yaml");

    private final ProjectFactService factService;
    private final ProjectFactStorage storage;

    /** 一条聊天发言；time 可空。 */
    public record ChatMessage(String sender, String time, String text) {
    }

    /** 聊天记录投递体（登录态与 /internal 通道共用）。 */
    public record ChatRequest(List<ChatMessage> messages, String visibility) {
    }

    /** 本机文件投递：落盘 + 写一条 FILE_UPLOAD 事实。 */
    public ProjectFact intakeFile(Long projectId, Long actorId, String fileName, InputStream in,
            String note, String visibility, LocalDateTime occurredAt) throws IOException {
        String safeName = StringUtils.hasText(fileName) ? fileName.strip() : "未命名文件";
        ProjectFactStorage.StoredFactFile stored = storage.store(projectId, safeName, in);
        String sourceRef = "file:" + projectId + ":" + stored.sha256();
        String body = mergeBody(note, textHead(stored, safeName));
        String title = StringUtils.hasText(note) ? safeName + " · " + note.strip() : safeName;
        append(projectId, ProjectFactService.SOURCE_FILE_UPLOAD, sourceRef, actorId, occurredAt,
                title, body, visibility);
        return factService.find(ProjectFactService.SOURCE_FILE_UPLOAD, sourceRef);
    }

    /** 补充说明：每次都是一条新事实。 */
    public ProjectFact intakeNote(Long projectId, Long actorId, String title, String body,
            String visibility) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String sourceRef = "note:" + UUID.randomUUID();
        append(projectId, ProjectFactService.SOURCE_NOTE, sourceRef, actorId, null,
                title.strip(), body, visibility);
        return factService.find(ProjectFactService.SOURCE_NOTE, sourceRef);
    }

    /** 聊天记录：整段拼成一条事实，同一段记录重传不新增。 */
    public ProjectFact intakeChat(Long projectId, Long actorId, List<ChatMessage> messages,
            String visibility) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("聊天记录至少 1 条");
        }
        List<String> lines = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            String sender = message.sender() == null ? "" : message.sender().strip();
            String text = message.text() == null ? "" : message.text().strip();
            String time = message.time() == null ? "" : message.time().strip();
            lines.add(time.isEmpty() ? sender + ": " + text : "[" + time + "] " + sender + ": " + text);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("聊天记录至少 1 条");
        }
        String body = String.join("\n", lines);
        String sourceRef = "chat:" + projectId + ":" + sha256Hex(body);
        String title = "聊天记录 " + lines.size() + " 条";
        append(projectId, ProjectFactService.SOURCE_CHAT_EXPORT, sourceRef, actorId, null,
                title, body, visibility);
        return factService.find(ProjectFactService.SOURCE_CHAT_EXPORT, sourceRef);
    }

    /**
     * 事实列表：<b>默认全部来源，不做类型过滤</b>；只有显式传了 types 才按传入的限定。
     *
     * <p>投递箱自己的口径（FILE_UPLOAD / NOTE / CHAT_EXPORT / ARTIFACT）由调用方显式传 types 表达，
     * 别再在这里给通用查询设默认值——制品证据里被引用的事实大多是 KEY_MATTER / WORKITEM /
     * MEETING / EMAIL，一旦默认收窄就会全被过滤成「不可见或已删除」。
     * 可见性仍统一由 {@code ProjectFactPolicy} 判定，本方法不掺和权限口径。
     */
    public List<ProjectFact> list(Long projectId, String types, int limit) {
        List<String> typeList = StringUtils.hasText(types)
                ? Arrays.stream(types.split(",")).map(String::strip)
                        .filter(StringUtils::hasText).toList()
                : null;
        return factService.query(projectId, typeList, null,
                limit <= 0 ? DEFAULT_LIST_LIMIT : limit);
    }

    private void append(Long projectId, String sourceType, String sourceRef, Long actorId,
            LocalDateTime occurredAt, String title, String body, String visibility) {
        factService.appendManual(projectId, sourceType, sourceRef, actorId,
                occurredAt == null ? LocalDateTime.now() : occurredAt,
                truncate(title, MAX_TITLE), body, visibility);
    }

    /** 文本类文件且不超过 256KB 时抽头部正文；否则 null。 */
    private String textHead(ProjectFactStorage.StoredFactFile stored, String fileName) {
        if (stored.sizeBytes() > TEXT_BODY_MAX_BYTES || !isTextLike(fileName)) {
            return null;
        }
        return storage.readText(stored.relativePath(), TEXT_BODY_CHARS);
    }

    private static String mergeBody(String note, String textHead) {
        boolean hasNote = StringUtils.hasText(note);
        boolean hasHead = StringUtils.hasText(textHead);
        if (hasNote && hasHead) {
            return note.strip() + "\n\n" + textHead.strip();
        }
        if (hasNote) {
            return note.strip();
        }
        return hasHead ? textHead.strip() : null;
    }

    private static boolean isTextLike(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha256.digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
