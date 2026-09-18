package com.bu.management.service;

import com.bu.management.entity.User;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 从周进展等自由文本中解析 @姓名 / @用户名。
 * 邮箱中的 @ 不计入；同名优先匹配更长的真实姓名。
 */
public final class MentionParser {

    private static final Pattern TOKEN = Pattern.compile("@([\\p{L}\\p{N}._\\-]{1,40})");

    private MentionParser() {}

    public record Mention(Long userId, String displayName, String token) {}

    public static List<Mention> resolve(String text, List<User> users) {
        if (!StringUtils.hasText(text) || users == null || users.isEmpty()) {
            return List.of();
        }
        Map<String, User> byToken = indexUsers(users);
        Matcher matcher = TOKEN.matcher(text);
        Map<Long, Mention> unique = new LinkedHashMap<>();
        while (matcher.find()) {
            if (isEmailAt(text, matcher.start())) {
                continue;
            }
            User matched = matchUser(matcher.group(1), byToken, users);
            if (matched == null || matched.getId() == null) {
                continue;
            }
            unique.putIfAbsent(matched.getId(), new Mention(
                    matched.getId(),
                    displayName(matched),
                    matcher.group(1)));
        }
        return List.copyOf(unique.values());
    }

    public static List<Mention> resolveAll(List<User> users, String... texts) {
        Map<Long, Mention> unique = new LinkedHashMap<>();
        if (texts == null) {
            return List.of();
        }
        for (String text : texts) {
            for (Mention mention : resolve(text, users)) {
                unique.putIfAbsent(mention.userId(), mention);
            }
        }
        return List.copyOf(unique.values());
    }

    private static Map<String, User> indexUsers(List<User> users) {
        Map<String, User> byToken = new LinkedHashMap<>();
        for (User user : users) {
            if (user == null || user.getId() == null) {
                continue;
            }
            putLonger(byToken, user.getUsername(), user);
            putLonger(byToken, user.getRealName(), user);
        }
        return byToken;
    }

    private static void putLonger(Map<String, User> byToken, String raw, User user) {
        String key = normalize(raw);
        if (key.isEmpty()) {
            return;
        }
        User existing = byToken.get(key);
        if (existing == null || displayName(user).length() > displayName(existing).length()) {
            byToken.put(key, user);
        }
    }

    private static User matchUser(String token, Map<String, User> byToken, List<User> users) {
        String key = normalize(token);
        User exact = byToken.get(key);
        if (exact != null) {
            return exact;
        }
        return users.stream()
                .filter(user -> user.getId() != null)
                .filter(user -> normalize(user.getRealName()).startsWith(key)
                        || normalize(user.getUsername()).startsWith(key))
                .max(Comparator.comparingInt(user -> displayName(user).length()))
                .orElse(null);
    }

    private static boolean isEmailAt(String text, int atIndex) {
        if (atIndex <= 0) {
            return false;
        }
        char before = text.charAt(atIndex - 1);
        return Character.isLetterOrDigit(before) || before == '.' || before == '_' || before == '-';
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(User user) {
        if (StringUtils.hasText(user.getRealName())) {
            return user.getRealName();
        }
        return user.getUsername() == null ? "" : user.getUsername();
    }
}
