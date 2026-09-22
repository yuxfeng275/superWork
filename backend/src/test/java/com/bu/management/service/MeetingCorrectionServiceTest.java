package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bu.management.integration.JevClient;
import com.bu.management.integration.MeetingSummaryClient;
import com.bu.management.service.MeetingTranscriptCodec.Segment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MeetingCorrectionService 测试")
class MeetingCorrectionServiceTest {

    @Mock
    private MeetingSummaryClient summaryClient;
    @Mock
    private JevClient jevClient;

    private ObjectMapper objectMapper;
    private MeetingCorrectionService correctionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        correctionService = new MeetingCorrectionService(summaryClient, jevClient, objectMapper);
    }

    @Test
    @DisplayName("术语表为空 → 原样返回，不调 LLM/Jev")
    void blankGlossary_returnsOriginal() {
        List<Segment> input = segments("先对齐 c d p 项目");
        assertThat(correctionService.correct(input, "  ")).isSameAs(input);
        assertThat(correctionService.correct(input, null)).isSameAs(input);
        verifyNoInteractions(summaryClient, jevClient);
    }

    @Test
    @DisplayName("合法纠偏被应用：仅 text 变化、edited=true，未改段原样")
    void validCorrections_applied() throws Exception {
        List<Segment> input = segments("大家好", "先对齐 c d p 项目的本期目标");
        when(jevClient.judge(any(), any())).thenReturn(Optional.empty());
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(
                "{\"corrections\":[{\"seq\":2,\"text\":\"先对齐 CDP 项目的本期目标\"}]}"));

        List<Segment> corrected = correctionService.correct(input, "CDP");

        assertThat(corrected).isNotSameAs(input);
        assertThat(corrected.get(1).text()).isEqualTo("先对齐 CDP 项目的本期目标");
        assertThat(corrected.get(1).edited()).isTrue();
        assertThat(corrected.get(0).text()).isEqualTo("大家好");
        assertThat(corrected.get(0).edited()).isFalse();
    }

    @Test
    @DisplayName("越界 seq 与未改动条目被忽略；全忽略时返回原引用")
    void outOfRangeAndNoop_ignored() throws Exception {
        List<Segment> input = segments("大家好");
        when(jevClient.judge(any(), any())).thenReturn(Optional.empty());
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(
                "{\"corrections\":[{\"seq\":99,\"text\":\"改动\"},{\"seq\":1,\"text\":\"大家好\"}]}"));

        assertThat(correctionService.correct(input, "CDP")).isSameAs(input);
    }

    @Test
    @DisplayName("长度暴增（超原文 2 倍 + 20 字）的纠偏被视为改写而忽略")
    void lengthExplosion_ignored() throws Exception {
        List<Segment> input = segments("短句");
        when(jevClient.judge(any(), any())).thenReturn(Optional.empty());
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(
                "{\"corrections\":[{\"seq\":1,\"text\":\"这是一段被大幅改写扩写的文本，远超原文长度，不应该被采纳为纠偏结果\"}]}"));

        assertThat(correctionService.correct(input, "CDP")).isSameAs(input);
    }

    @Test
    @DisplayName("LLM 调用失败 → 保持原稿")
    void llmFailure_keepsOriginal() {
        List<Segment> input = segments("先对齐 c d p 项目");
        when(jevClient.judge(any(), any())).thenReturn(Optional.empty());
        when(summaryClient.chatJson(any(), any()))
                .thenThrow(new IllegalStateException("AI 模型未配置或未启用"));

        assertThat(correctionService.correct(input, "CDP")).isSameAs(input);
    }

    @Test
    @DisplayName("Jev 判定无需纠偏 → 不调 LLM，原样返回")
    void jevSkips_llmNotCalled() {
        List<Segment> input = segments("一切正常的发言");
        when(jevClient.judge(any(), any())).thenReturn(Optional.of(false));

        assertThat(correctionService.correct(input, "CDP")).isSameAs(input);
        verifyNoInteractions(summaryClient);
    }

    @Test
    @DisplayName("Jev 判定纠偏提案不可采纳 → 返回原稿")
    void jevRejects_keepsOriginal() throws Exception {
        List<Segment> input = segments("先对齐 c d p 项目");
        when(jevClient.judge(any(), any()))
                .thenReturn(Optional.of(true))
                .thenReturn(Optional.of(false));
        when(summaryClient.chatJson(any(), any())).thenReturn(objectMapper.readTree(
                "{\"corrections\":[{\"seq\":1,\"text\":\"先对齐 CDP 项目\"}]}"));

        assertThat(correctionService.correct(input, "CDP")).isSameAs(input);
    }

    private static List<Segment> segments(String... texts) {
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            result.add(new Segment(i + 1, i * 1000L, (i + 1) * 1000L, "SPEAKER_00", texts[i], false));
        }
        return result;
    }
}
