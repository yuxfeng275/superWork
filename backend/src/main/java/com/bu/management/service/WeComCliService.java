package com.bu.management.service;

import com.bu.management.integration.WeComCliClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 企业微信机器人通道（wecom-cli）业务层：授权管理 + 待办/日程/通讯录/文档/消息/邮件的真实取数与写入。
 *
 * <p>配置全部来自「连接器管理 → 企业微信」卡片（Bot ID 在 extra_config，Bot Secret 在加密列），
 * 本层不引入任何新的配置入口。CLI 二进制与凭据目录由部署环境提供（镜像内置 + 数据卷）。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeComCliService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final WeComCliClient cli;
    private final ObjectMapper objectMapper;

    // ==================== 状态与授权 ====================

    /** 机器人通道状态（连接器页展示）。 */
    public Map<String, Object> status() {
        WeComCliClient.CliStatus status = cli.status();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cliInstalled", !status.hint().startsWith("wecom-cli 未安装"));
        result.put("authorized", status.authorized());
        result.put("botId", status.botId());
        result.put("hint", status.hint());
        result.put("botIdConfigured", StringUtils.hasText(cli.botId()));
        result.put("botSecretConfigured", StringUtils.hasText(cli.botSecret()));
        return result;
    }

    /** 用连接器里保存的 Bot 凭证执行授权（无人值守）。 */
    public Map<String, Object> authorizeWithStoredCredentials() {
        String botId = cli.botId();
        String botSecret = cli.botSecret();
        if (!StringUtils.hasText(botId) || !StringUtils.hasText(botSecret)) {
            throw new IllegalStateException("请先在连接器里填写并保存 Bot ID 与 Bot Secret");
        }
        WeComCliClient.CliStatus status = cli.authorizeWithBotCredentials(botId, botSecret);
        cli.invalidateCapabilities();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authorized", status.authorized());
        result.put("botId", status.botId());
        result.put("hint", status.hint());
        return result;
    }

    /** 发起扫码授权。 */
    public Map<String, Object> startQrAuthorization() {
        WeComCliClient.QrSession session = cli.startQrAuthorization();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.sessionId());
        result.put("imageBase64", session.imageBase64());
        result.put("expireAt", session.expireAt());
        return result;
    }

    /** 扫码进度。 */
    public Map<String, Object> pollQrAuthorization(String sessionId) {
        Map<String, Object> result = cli.qrPoll(sessionId);
        if ("authorized".equals(result.get("status"))) {
            cli.invalidateCapabilities();
        }
        return result;
    }

    /** 品类授权体检。 */
    public List<WeComCliClient.Capability> capabilities(boolean refresh) {
        return cli.capabilities(refresh);
    }

    // ==================== 待办 ====================

    /** 待办列表（机器人视角：机器人为创建人、对话人为参与人）。 */
    public List<Map<String, Object>> listTodos(int limit, List<String> statusFilter) {
        List<String> args = new ArrayList<>(List.of("list", "--limit", String.valueOf(clamp(limit))));
        if (statusFilter != null && !statusFilter.isEmpty()) {
            args.add("--status-filter");
            args.addAll(statusFilter);
        }
        JsonNode payload = cli.execOrThrow("todo", args);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : payload.path("items")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("todoId", item.path("todo_id").asText(null));
            row.put("title", item.path("title").asText(""));
            row.put("description", item.path("description").asText(""));
            // creator / deadline 为对象（{userid,user_name} / {type,value}），不能按文本直读
            row.put("creator", item.path("creator").path("user_name").asText(""));
            row.put("deadline", item.path("deadline").path("value").asText(null));
            row.put("status", item.path("status").asText(""));
            row.put("createTime", item.path("create_time").asText(null));
            row.put("updateTime", item.path("update_time").asText(null));
            row.put("reminder", item.path("extra_info").asText(""));
            row.put("source", item.path("source").asText(""));
            List<String> followers = new ArrayList<>();
            List<String> followerIds = new ArrayList<>();
            for (JsonNode follower : item.path("followers")) {
                String name = follower.path("user_name").asText("");
                if (StringUtils.hasText(name)) followers.add(name);
                String userid = follower.path("userid").asText("");
                if (StringUtils.hasText(userid)) followerIds.add(userid);
            }
            row.put("followers", followers);
            row.put("followerIds", followerIds);
            rows.add(row);
        }
        return rows;
    }

    /** 创建待办（单条便捷入口）。deadline 需为对象 {type,value}（企微要求，非字符串）。 */
    public JsonNode createTodo(String title, String description, String deadline, List<String> followerIds) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("待办标题不能为空");
        }
        ObjectNode item = objectMapper.createObjectNode();
        item.put("title", title.trim());
        if (StringUtils.hasText(description)) item.put("description", description.trim());
        if (StringUtils.hasText(deadline)) {
            String value = deadline.trim();
            ObjectNode deadlineNode = item.putObject("deadline");
            // 只给日期按 date 类型，含时刻按 datetime（与企微枚举一致）
            deadlineNode.put("type", value.length() <= 10 ? "date" : "datetime");
            deadlineNode.put("value", value);
        }
        if (followerIds != null && !followerIds.isEmpty()) {
            ArrayNode ids = item.putArray("follower_ids");
            followerIds.stream().filter(StringUtils::hasText).forEach(ids::add);
        }
        ArrayNode items = objectMapper.createArrayNode();
        items.add(item);
        return cli.execOrThrow("todo", List.of("create", "--items", items.toString()));
    }

    /** 完成待办（批量）。 */
    public JsonNode finishTodos(List<String> todoIds) {
        if (todoIds == null || todoIds.isEmpty()) {
            throw new IllegalArgumentException("待办 ID 不能为空");
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (String id : todoIds) {
            if (!StringUtils.hasText(id)) continue;
            ObjectNode node = objectMapper.createObjectNode();
            node.put("todo_id", id.trim());
            items.add(node);
        }
        return cli.execOrThrow("todo", List.of("finish", "--items", items.toString()));
    }

    // ==================== 日程 ====================

    /**
     * 日程列表（企微限制：只能查询当天前后 30 天以内）。
     * 默认近 7 天到未来 7 天。
     */
    public List<Map<String, Object>> listSchedules(String beginTime, String endTime, int limit) {
        LocalDateTime now = LocalDateTime.now();
        String begin = StringUtils.hasText(beginTime) ? beginTime : now.minusDays(7).format(TIME);
        String end = StringUtils.hasText(endTime) ? endTime : now.plusDays(7).format(TIME);
        JsonNode payload = cli.execOrThrow("calendar", List.of(
                "schedules", "list", "--begin-time", begin, "--end-time", end));
        List<Map<String, Object>> rows = new ArrayList<>();
        int count = 0;
        for (JsonNode item : payload.path("schedule_list")) {
            if (count++ >= clamp(limit)) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scheduleId", item.path("schedule_id").asText(""));
            row.put("subject", item.path("subject").asText(""));
            row.put("beginTime", item.path("begin_time").asText(""));
            row.put("endTime", item.path("end_time").asText(""));
            row.put("location", item.path("location").asText(""));
            row.put("creator", item.path("creator_name").asText(""));
            row.put("calendarName", item.path("calendar_name").asText(""));
            row.put("description", item.path("description").asText(""));
            rows.add(row);
        }
        return rows;
    }

    // ==================== 通讯录 ====================

    /** 成员搜索（姓名/拼音/别名）。 */
    public List<Map<String, Object>> searchContacts(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        JsonNode payload = cli.execOrThrow("contact", List.of("users", "search", "--keywords", keyword.trim()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode user : payload.path("users")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", user.path("name").asText(""));
            row.put("alias", user.path("alias").asText(""));
            row.put("position", user.path("position").asText(""));
            row.put("email", user.path("email").asText(""));
            List<String> departments = new ArrayList<>();
            for (JsonNode dept : user.path("departments")) {
                String name = dept.path("name").asText(dept.asText(""));
                if (StringUtils.hasText(name)) departments.add(name);
            }
            row.put("departments", departments);
            row.put("userid", user.path("userid").asText(""));
            rows.add(row);
        }
        return rows;
    }

    // ==================== 文档 ====================

    /** 文档搜索。 */
    public List<Map<String, Object>> searchDocs(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        JsonNode payload = cli.execOrThrow("doc", List.of("search", "--keywords", keyword.trim(),
                "--limit", String.valueOf(clamp(limit))));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode doc : payload.path("docs")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", doc.path("docid").asText(""));
            row.put("name", doc.path("doc_name").asText(""));
            row.put("type", doc.path("doc_type").asText(""));
            row.put("creator", doc.path("creator_name").asText(""));
            row.put("modifyTime", doc.path("modify_time").asText(null));
            row.put("url", doc.path("url").asText(""));
            rows.add(row);
        }
        return rows;
    }

    /** 读取文档内容（markdown；内容超长时 CLI 落盘为文件，需从工作目录读回）。 */
    public String readDoc(String docId) {
        if (!StringUtils.hasText(docId)) {
            throw new IllegalArgumentException("文档 ID 不能为空");
        }
        JsonNode payload = cli.execOrThrow("doc", List.of("contents", "get", "--docid", docId.trim()));
        String content = payload.path("content").asText("");
        String filePath = payload.path("file_path").asText("");
        if (StringUtils.hasText(filePath)) {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(filePath));
            } catch (Exception e) {
                log.warn("读取文档落盘内容失败: {}", e.getMessage());
            }
        }
        if (StringUtils.hasText(content)) {
            return content;
        }
        return payload.toString();
    }

    // ==================== 消息 / 邮件 ====================

    /** 向单聊或群聊发送文本消息（chatId 为成员 userid 或群会话 ID）。 */
    public JsonNode sendMessage(String chatId, String text) {
        if (!StringUtils.hasText(chatId) || !StringUtils.hasText(text)) {
            throw new IllegalArgumentException("会话 ID 与消息内容均不能为空");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("text", text);
        return cli.execOrThrow("message", List.of("send", "--chat-id", chatId.trim(),
                "--msg-type", "text", "--json", payload.toString()));
    }

    /** 邮件检索（只读）。 */
    public List<Map<String, Object>> searchMail(String keyword, int limit) {
        List<String> args = new ArrayList<>(List.of("search", "--limit", String.valueOf(clamp(limit))));
        if (StringUtils.hasText(keyword)) {
            args.add("--keywords");
            args.add(keyword.trim());
        }
        JsonNode payload = cli.execOrThrow("mail", args);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode mail : payload.path("mails")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mailId", mail.path("mail_id").asText(""));
            row.put("subject", mail.path("subject").asText(""));
            row.put("sender", mail.path("sender").asText(""));
            row.put("sendTime", mail.path("send_time").asText(null));
            row.put("isRead", mail.path("is_read").asBoolean(false));
            rows.add(row);
        }
        return rows;
    }

    // ==================== 辅助 ====================

    private int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private String jsonArray(String value) {
        ArrayNode array = objectMapper.createArrayNode();
        array.add(value);
        return array.toString();
    }
}
