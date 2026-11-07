package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiBrief;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.integration.BarkClient;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.AiBriefMapper;
import com.bu.management.mapper.EmailWeComMappingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 晨报推送正文：JSON 结构要排成人能读的清单（不能把原始 JSON 推到手机上），
 * 解析不了则退回纯文本；总长 900 字以内，Bark 标题带晨报日期。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectBriefPushServiceTest {

    private static final String JSON_CONTENT = "{\"items\":["
            + "{\"title\":\"确认会员中心改版范围\",\"why\":\"需求 128 的方案待拍板，晚一天影响迭代排期\","
            + "\"links\":[\"https://example.com/requirements/128\",\"https://example.com/other\"]},"
            + "{\"title\":\"补对账空值的回归用例\",\"why\":\"昨晚缺陷 D-77 修复后缺用例\",\"links\":[]},"
            + "{\"title\":\"和财务对齐回款口径\",\"why\":\"季度结账前必须确认\",\"links\":[\"https://example.com/finance/9\"]},"
            + "{\"title\":\"第四条不该出现\",\"why\":\"超出三条上限\",\"links\":[]}"
            + "]}";

    @Mock private AiBriefMapper briefMapper;
    @Mock private EmailWeComMappingMapper mappingMapper;
    @Mock private ProjectBriefService briefService;
    @Mock private WeComClient weComClient;
    @Mock private BarkClient barkClient;

    private ProjectBriefPushService service;

    @BeforeEach
    void setUp() {
        service = new ProjectBriefPushService(
                briefMapper, mappingMapper, briefService, weComClient, barkClient, new ObjectMapper());
    }

    @Test
    @DisplayName("render：JSON content 排成清单，不再出现原始 JSON 片段")
    void renderTurnsJsonIntoReadableList() {
        String text = service.render(brief(JSON_CONTENT));

        assertThat(text).doesNotContain("{\"items\"");
        assertThat(text).doesNotContain("\"why\"");
        // 第一条的标题与 why 片段都在
        assertThat(text).contains("确认会员中心改版范围");
        assertThat(text).contains("需求 128 的方案待拍板");
        // 首条第一个 http 链接整行输出，非 http 链接丢弃
        assertThat(text).contains("\n   https://example.com/requirements/128");
        assertThat(text).doesNotContain("https://example.com/other");
        // 标题带日期，正文行首不留额外空白
        assertThat(text).startsWith("【今日安排】 · 2026-03-02");
        assertThat(text.lines().anyMatch(line -> line.matches("\\d\\. .*"))).isTrue();
        assertThat(text.lines().anyMatch(line -> line.startsWith(" 1."))).isFalse();
        // 最多 3 条
        assertThat(text).contains("3. 和财务对齐回款口径");
        assertThat(text).doesNotContain("第四条不该出现");
        assertThat(text.length()).isLessThanOrEqualTo(900);
    }

    @Test
    @DisplayName("render：why 超过 120 字截断，总长不超过 900 字")
    void renderClipsWhyAndBody() {
        String longWhy = "很长的原因".repeat(100);
        String content = "{\"items\":[{\"title\":\"标题\",\"why\":\"" + longWhy + "\",\"links\":[]}]}";

        String text = service.render(brief(content));

        assertThat(text).contains("标题");
        assertThat(text).contains("…");
        assertThat(text.length()).isLessThanOrEqualTo(900);
        assertThat(text).doesNotContain(longWhy);
    }

    @Test
    @DisplayName("render：不是 {\"items\":[...]} 结构时退回纯文本前 800 字")
    void renderFallsBackToPlainText() {
        String plain = "x".repeat(1000);
        String text = service.render(brief(plain));

        assertThat(text).startsWith("【今日安排】");
        assertThat(text).contains(plain.substring(0, 800));
        assertThat(text.length()).isLessThan(1000);

        // 合法 JSON 但没有 items 数组，同样退回纯文本
        String noItems = "{\"note\":\"今天没排事\"}";
        assertThat(service.render(brief(noItems))).contains(noItems);
    }

    @Test
    @DisplayName("pushOne：Bark 标题带晨报日期，正文用同一份清单")
    void barkTitleCarriesBriefDate() {
        AiBrief brief = brief(JSON_CONTENT);
        when(briefMapper.selectList(any())).thenReturn(List.of(brief));
        when(mappingMapper.selectOne(any())).thenReturn((EmailWeComMapping) null);
        when(barkClient.isConfigured()).thenReturn(true);
        when(barkClient.push(any(), any(), any())).thenReturn(true);

        assertThat(service.pushDate(LocalDate.of(2026, 3, 2))).isEqualTo(1);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(barkClient).push(title.capture(), body.capture(), isNull());
        assertThat(title.getValue()).isEqualTo("【今日安排】 · 2026-03-02");
        assertThat(body.getValue()).doesNotContain("{\"items\"");
        verify(briefService).markDelivered(eq(7L));
    }

    private AiBrief brief(String content) {
        AiBrief brief = new AiBrief();
        brief.setId(7L);
        brief.setUserId(9L);
        brief.setProjectId(0L);
        brief.setBriefDate(LocalDate.of(2026, 3, 2));
        brief.setContent(content);
        return brief;
    }
}
