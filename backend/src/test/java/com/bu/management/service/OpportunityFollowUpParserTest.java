package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 跟进问句解析：时间说法与回复结构化，人话解析不出来必须返回 null（不猜）。
 */
class OpportunityFollowUpParserTest {

    /** 2026-11-11 是周三。 */
    private final LocalDate wednesday = LocalDate.of(2026, 11, 11);

    @Test
    void 解析今天() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("今天下午聊一下", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 0));
    }

    @Test
    void 解析明天带时间() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("明天 15:30 再打电话", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 12, 15, 30));
    }

    @Test
    void 解析后天与大后天() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("后天再说", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 13, 9, 0));
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("大后天跟进", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 14, 9, 0));
    }

    @Test
    void 解析周三() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("周三再约", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 11, 9, 0));
        // 今天周五说「周三」→ 下周三
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("周三再约", wednesday.plusDays(2)))
                .isEqualTo(LocalDateTime.of(2026, 11, 18, 9, 0));
    }

    @Test
    void 解析下周三带时间() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("下周三 10:00 过方案", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 18, 10, 0));
    }

    @Test
    void 解析月日() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("11-12 再联系", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 12, 9, 0));
        // 已过去的月日顺延到明年
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("01-05 再联系", wednesday))
                .isEqualTo(LocalDateTime.of(2027, 1, 5, 9, 0));
    }

    @Test
    void 解析完整日期时间() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("2026-11-12 15:00 见客户", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 12, 15, 0));
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("2026-11-12", wednesday))
                .isEqualTo(LocalDateTime.of(2026, 11, 12, 9, 0));
    }

    @Test
    void 垃圾文本返回空() {
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("好的收到，我再想想", wednesday)).isNull();
        assertThat(OpportunityFollowUpParser.parseNextFollowUp("", wednesday)).isNull();
        assertThat(OpportunityFollowUpParser.parseNextFollowUp(null, wednesday)).isNull();
    }

    @Test
    void 回复解析出阶段概率与下次时间() {
        OpportunityFollowUpParser.ReplyDraft draft =
                OpportunityFollowUpParser.parseReply("已经进入商务谈判，概率 70%，下周三 15:00 再谈价格细节");
        assertThat(draft).isNotNull();
        assertThat(draft.stage()).isEqualTo("商务谈判");
        assertThat(draft.probability()).isEqualTo(70);
        assertThat(draft.nextAt()).isNotNull();
        assertThat(draft.confidence()).isBetween(0.2, 1.0);
    }

    @Test
    void 回复解析不认识的阶段不落白名单外取值() {
        OpportunityFollowUpParser.ReplyDraft draft =
                OpportunityFollowUpParser.parseReply("客户说还在评估，暂时没结论");
        assertThat(draft).isNotNull();
        assertThat(draft.stage()).isNull();
        assertThat(draft.probability()).isNull();
    }

    @Test
    void 超范围概率不采纳() {
        OpportunityFollowUpParser.ReplyDraft draft =
                OpportunityFollowUpParser.parseReply("概率 180% 吧");
        assertThat(draft).isNotNull();
        assertThat(draft.probability()).isNull();
    }
}
