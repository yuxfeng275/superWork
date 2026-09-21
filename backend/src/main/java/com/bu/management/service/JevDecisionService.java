package com.bu.management.service;

import com.bu.management.integration.JevClient;
import com.bu.management.vo.AiAgentToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * TypeSafe Jev 决策层：意图路由收窄工具、写操作门禁。
 * 未配置或调用失败一律 fail-open，不阻断 DeepSeek 对话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JevDecisionService {

    static final double INTENT_CONFIDENCE = 0.65;
    static final double WRITE_CONFIDENCE = 0.70;
    static final double WRITE_NOUL = 0.55;

    static final Set<String> WRITE_TOOLS = Set.of(
            "reply_my_email",
            "convert_email_item_to_task",
            "weekly_report_input",
            "wecom_send_message",
            "wecom_create_todo",
            "wecom_finish_todo");

    private static final Map<String, Set<String>> INTENT_TOOLS = Map.of(
            "hours", Set.of(
                    "query_my_worklogs", "query_my_worktime", "analyze_my_worktime",
                    "analyze_team_worktime", "analyze_my_contracts", "analyze_cost_structure",
                    "get_kpi_summary", "get_worktime_sync_status"),
            "mail", Set.of(
                    "search_my_emails", "read_my_email", "get_my_email_digest",
                    "convert_email_item_to_task", "reply_my_email", "wecom_search_mail"),
            "yunxiao", Set.of(
                    "query_yunxiao_projects", "query_yunxiao_workitems", "get_yunxiao_workitem",
                    "query_my_tasks", "query_my_requirements", "count_my_issues"),
            "yuque", Set.of(
                    "search_yuque_docs", "read_yuque_doc", "wecom_search_docs", "wecom_read_doc"),
            "weekly", Set.of(
                    "weekly_report_facts", "weekly_report_input",
                    "query_my_tasks", "query_my_requirements"),
            "oa", Set.of("query_oa_pending", "query_oa_done", "get_oa_flow"),
            "wecom", Set.of(
                    "wecom_search_contact", "wecom_list_todos", "wecom_create_todo",
                    "wecom_finish_todo", "wecom_list_schedules", "wecom_search_docs",
                    "wecom_read_doc", "wecom_send_message", "wecom_search_mail"));

    private static final Map<String, String> INTENT_LABELS = Map.of(
            "hours", "工时",
            "mail", "邮件",
            "yunxiao", "云效",
            "yuque", "语雀",
            "weekly", "周报",
            "oa", "OA",
            "wecom", "企微",
            "other", "综合");

    private final AiModelConfigService modelConfigService;
    private final JevClient jevClient;

    public record IntentDecision(
            boolean ready,
            String model,
            String intent,
            double confidence,
            boolean writeLikely,
            String summary) {
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "jev_decision");
            payload.put("ready", ready);
            payload.put("model", model);
            payload.put("intent", intent);
            payload.put("confidence", confidence);
            payload.put("writeLikely", writeLikely);
            payload.put("summary", summary);
            return payload;
        }
    }

    public record WriteGate(boolean ready, boolean allow, String action, String reason) {
    }

    public IntentDecision classifyIntent(String userMessage) {
        Optional<Resolved> resolved = resolve();
        if (resolved.isEmpty() || !StringUtils.hasText(userMessage)) {
            return skipped("other");
        }
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("intent", Map.of(
                "type", "choice",
                "instructions", "What is the user's primary request about?",
                "criteria", Map.of(
                        "hours", "Worklogs, hours filling, KPI labor",
                        "mail", "Email search, digest, reply",
                        "yunxiao", "Yunxiao / requirements / tasks / bugs",
                        "yuque", "Yuque knowledge docs",
                        "weekly", "Weekly report or weekly meeting",
                        "oa", "Seeyon OA todos or workflows",
                        "wecom", "WeCom chat, todos, schedule, docs",
                        "other", "None of the above or mixed")));
        questions.put("needs_write", Map.of(
                "type", "noul",
                "instructions", "Does the user want a write action (send email, create todo, write weekly draft)?",
                "criteria", Map.of(
                        "true", "They ask to send, create, convert, or persist",
                        "false", "Read-only query or analysis")));
        questions.put("urgency", Map.of(
                "type", "score",
                "instructions", "How time-sensitive is this request?",
                "criteria", List.of("Routine", "Soon", "Urgent")));
        try {
            JevClient.Evaluation evaluation = jevClient.evaluate(
                    resolved.get().baseUrl(), resolved.get().apiKey(), resolved.get().model(),
                    Map.of("user_message", userMessage), questions);
            String intent = normalizeIntent(evaluation.choice("intent"));
            double confidence = evaluation.confidence("intent");
            boolean writeLikely = evaluation.noul("needs_write") >= WRITE_NOUL;
            String summary = "Jev " + evaluation.model()
                    + " · 意图 " + INTENT_LABELS.getOrDefault(intent, intent)
                    + " " + percent(confidence)
                    + (writeLikely ? " · 可能写操作" : " · 只读");
            return new IntentDecision(true, evaluation.model(), intent, confidence, writeLikely, summary);
        } catch (RuntimeException e) {
            log.warn("Jev 意图路由失败，回落全工具: {}", e.getMessage());
            return skipped("other");
        }
    }

    public List<AiAgentToolDefinition> filterTools(IntentDecision decision, List<AiAgentToolDefinition> tools) {
        if (decision == null || !decision.ready() || decision.confidence() < INTENT_CONFIDENCE) {
            return tools;
        }
        Set<String> allowed = INTENT_TOOLS.get(decision.intent());
        if (allowed == null || allowed.isEmpty()) {
            return tools;
        }
        return tools.stream()
                .filter(tool -> allowed.contains(tool.name())
                        || (decision.writeLikely() && WRITE_TOOLS.contains(tool.name())))
                .toList();
    }

    public WriteGate gateWrite(String toolName, String userMessage, String argsJson) {
        if (!WRITE_TOOLS.contains(toolName)) {
            return new WriteGate(false, true, "skip", "");
        }
        Optional<Resolved> resolved = resolve();
        if (resolved.isEmpty()) {
            return new WriteGate(false, true, "skip", "");
        }
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("action", Map.of(
                "type", "choice",
                "instructions", "The assistant is about to run a write tool. What should happen?",
                "criteria", Map.of(
                        "execute", "The user already confirmed this exact action",
                        "recap", "Need to restate the action and wait for confirmation",
                        "refuse", "The action is unsafe or not requested",
                        "escalate", "A human should decide")));
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tool_name", toolName);
        state.put("user_message", userMessage == null ? "" : userMessage);
        state.put("tool_args", argsJson == null ? "" : argsJson);
        try {
            JevClient.Evaluation evaluation = jevClient.evaluate(
                    resolved.get().baseUrl(), resolved.get().apiKey(), resolved.get().model(),
                    state, questions);
            String action = evaluation.choice("action");
            if (!StringUtils.hasText(action)) {
                action = "recap";
            }
            action = action.toLowerCase(Locale.ROOT);
            double confidence = evaluation.confidence("action");
            boolean allow = "execute".equals(action) && confidence >= WRITE_CONFIDENCE;
            String reason = allow
                    ? "Jev 已放行写操作（" + percent(confidence) + "）"
                    : "Jev 拦截写操作：需要先向用户复述并获得确认（判定 "
                            + action + "，置信 " + percent(confidence) + "）";
            return new WriteGate(true, allow, action, reason);
        } catch (RuntimeException e) {
            log.warn("Jev 写操作门禁失败，fail-open: tool={}, error={}", toolName, e.getMessage());
            return new WriteGate(false, true, "skip", "");
        }
    }

    public boolean isWriteTool(String toolName) {
        return WRITE_TOOLS.contains(toolName);
    }

    private Optional<Resolved> resolve() {
        Optional<AiModelConfigService.DecisionModel> model = modelConfigService.decisionModel();
        if (model.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(model.get().baseUrl(), model.get().apiKey(), model.get().model()));
    }

    private IntentDecision skipped(String intent) {
        return new IntentDecision(false, "", intent, 0, false, "");
    }

    private String normalizeIntent(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return INTENT_LABELS.containsKey(value) ? value : "other";
    }

    private String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private record Resolved(String baseUrl, String apiKey, String model) {
    }
}
