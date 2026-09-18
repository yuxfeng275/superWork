package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bu.management.entity.User;
import java.util.List;
import org.junit.jupiter.api.Test;

class MentionParserTest {

    @Test
    void extractsDistinctMentionsByRealNameAndUsername() {
        User zhang = user(7L, "zhangsan", "张三");
        User li = user(8L, "lisi", "李四");
        User wang = user(9L, "wangwu", "王五");

        List<MentionParser.Mention> mentions = MentionParser.resolve(
                "@张三 请确认上线窗口，抄送 @lisi；邮箱 foo@bar.com 不算。",
                List.of(zhang, li, wang));

        assertThat(mentions).extracting(MentionParser.Mention::userId)
                .containsExactly(7L, 8L);
    }

    @Test
    void prefersLongerRealNameAndIgnoresUnknownTokens() {
        User shortName = user(1L, "zhang", "张");
        User longName = user(2L, "zhangsanfeng", "张三丰");

        List<MentionParser.Mention> mentions = MentionParser.resolve(
                "请 @张三丰 处理，另外 @不存在 忽略",
                List.of(shortName, longName));

        assertThat(mentions).extracting(MentionParser.Mention::userId)
                .containsExactly(2L);
    }

    @Test
    void scansAllWeeklyFields() {
        User user = user(3L, "chen", "陈晨");

        List<MentionParser.Mention> mentions = MentionParser.resolveAll(
                List.of(user),
                "进展正常",
                "风险：@陈晨 需要盯一下",
                null,
                "");

        assertThat(mentions).extracting(MentionParser.Mention::displayName)
                .containsExactly("陈晨");
    }

    private User user(Long id, String username, String realName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        user.setStatus(1);
        return user;
    }
}
