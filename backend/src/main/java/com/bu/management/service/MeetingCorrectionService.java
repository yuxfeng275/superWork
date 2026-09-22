package com.bu.management.service;

import com.bu.management.integration.JevClient;
import com.bu.management.integration.MeetingSummaryClient;
import com.bu.management.service.MeetingTranscriptCodec.Segment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 会议转写纠偏：LLM 出纠偏提案（只修术语/专名误识别），可选 Jev 前后两道守门
 * （是否需要纠偏 / 提案是否安全采纳）。
 * 任何失败或不可信 → 返回原稿，绝不阻断流水线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingCorrectionService {

    /** 纠偏输入上限（与总结同口径） */
    private static final int INPUT_CAP = 120_000;

    private static final String SYSTEM_PROMPT = """
            你是会议转写校对助手。输入是一次会议语音识别转写 JSON（segments，含 seq/speaker/text）和领域术语表 glossary。
            任务：仅修正明显的术语/专名识别错误——音近字错（如 买点→埋点、马测→MA、皮看板→BI）、英文缩写被拆成带空格字母或错拼（如 c m→CRM、c d p→CDP）、被截断的缩写补全。
            只允许修改 text 字段；不得改写句意、不得增删内容、不得合并/拆分/重排段落；拿不准的不改。
            输出严格 JSON：{"corrections":[{"seq":3,"text":"修正后的该段完整文本"}]}，只列出有修改的段；无需修改则输出 {"corrections":[]}。
            """;

    private final MeetingSummaryClient summaryClient;
    private final JevClient jevClient;
    private final AiModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 纠偏入口。返回纠偏后的段列表（改动段 edited=true）；未改动时返回入参原引用（调用方用 == 判断）。
     */
    public List<Segment> correct(List<Segment> segments, String glossary) {
        if (!StringUtils.hasText(glossary) || segments.isEmpty()) {
            return segments;
        }
        String input;
        try {
            List<String> terms = Arrays.stream(glossary.split("[,，]"))
                    .map(String::trim).filter(StringUtils::hasText).toList();
            if (terms.isEmpty()) {
                return segments;
            }
            input = objectMapper.writeValueAsString(Map.of(
                    "glossary", terms,
                    "segments", segments.stream()
                            .map(s -> Map.of("seq", s.seq(),
                                    "speaker", s.speaker() == null ? "" : s.speaker(),
                                    "text", s.text()))
                            .toList()));
        } catch (Exception e) {
            log.warn("纠偏输入构造失败，保持原稿：{}", e.getMessage());
            return segments;
        }
        if (input.length() > INPUT_CAP) {
            return segments;
        }

        // Jev 门控 1：是否需要纠偏（连接器未就绪 → 跳过门控，直接纠偏）
        Optional<Boolean> needed = judge(input,
                "这是一次会议的语音识别转写（segments）与领域术语表（glossary）。转写中是否存在疑似术语/专名误识别"
                        + "（音近字错、英文缩写被拆成带空格的字母或错拼），需要纠偏？");
        if (needed.isPresent() && !needed.get()) {
            log.info("Jev 判定转写无需纠偏，跳过");
            return segments;
        }

        JsonNode result;
        try {
            result = summaryClient.chatJson(SYSTEM_PROMPT, input);
        } catch (RuntimeException e) {
            log.warn("转写纠偏调用失败，保持原稿：{}", e.getMessage());
            return segments;
        }
        List<Segment> corrected = apply(segments, result);
        if (corrected == segments) {
            return segments;
        }

        // Jev 门控 2：纠偏提案是否安全采纳（仅当有改动时）
        Optional<Boolean> accept = judge(diffOf(segments, corrected),
                "上面是会议转写纠偏对照（每行：seq | 原文 → 纠偏）。纠偏是否仅修正了术语/专名识别错误、语义与原文一致、可以采纳？");
        if (accept.isPresent() && !accept.get()) {
            log.warn("Jev 判定纠偏提案不可采纳，保持原稿");
            return segments;
        }
        return corrected;
    }

    /** 应用纠偏：未知 seq / 空文本 / 与原句相同 / 长度异常暴增的条目忽略。无改动时返回入参原引用。 */
    private List<Segment> apply(List<Segment> segments, JsonNode result) {
        JsonNode corrections = result == null ? null : result.path("corrections");
        if (corrections == null || !corrections.isArray()) {
            return segments;
        }
        Map<Integer, Integer> indexBySeq = new HashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            indexBySeq.put(segments.get(i).seq(), i);
        }
        List<Segment> out = null;
        for (JsonNode item : corrections) {
            if (!item.path("seq").isNumber()) {
                continue;
            }
            Integer index = indexBySeq.get(item.path("seq").asInt());
            String text = item.path("text").isTextual() ? item.path("text").asText().strip() : null;
            if (index == null || !StringUtils.hasText(text)) {
                continue;
            }
            Segment origin = segments.get(index);
            if (text.equals(origin.text())) {
                continue;
            }
            // 长度暴增（超过原文 2 倍 + 20 字符）视为改写，不采纳
            if (text.length() > origin.text().length() * 2 + 20) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(segments);
            }
            out.set(index, new Segment(origin.seq(), origin.startMs(), origin.endMs(),
                    origin.speaker(), text, true));
        }
        return out == null ? segments : out;
    }

    /** 决策模型（Jev/System One）守门：模型管理未勾选「决策」或调用失败 → empty（无门控）。 */
    private Optional<Boolean> judge(String state, String instructions) {
        Optional<AiModelConfigService.DecisionModel> model = modelConfigService.decisionModel();
        if (model.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> questions = Map.of("judge", Map.of(
                "type", "noul",
                "instructions", instructions));
        try {
            JevClient.Evaluation evaluation = jevClient.evaluate(
                    model.get().baseUrl(), model.get().apiKey(), model.get().model(),
                    state, questions, jevClient.connectorProxy());
            boolean accepted = evaluation.noul("judge") >= 0.5;
            log.info("Jev 判定：{}… → {}（noul={}，model={}）",
                    instructions.substring(0, Math.min(24, instructions.length())),
                    accepted ? "是" : "否", evaluation.noul("judge"), evaluation.model());
            return Optional.of(accepted);
        } catch (RuntimeException e) {
            log.warn("Jev 判定失败（降级为无门控）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String diffOf(List<Segment> before, List<Segment> after) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).text().equals(after.get(i).text())) {
                sb.append(before.get(i).seq()).append(" | ")
                        .append(before.get(i).text()).append(" → ")
                        .append(after.get(i).text()).append('\n');
            }
        }
        return sb.toString();
    }
}
