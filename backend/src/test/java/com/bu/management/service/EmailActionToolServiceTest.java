package com.bu.management.service;

import com.bu.management.vo.AiAgentToolDefinition;
import com.bu.management.vo.AiAgentToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮件行动工具：定义下发、参数校验、转化与回复分发。
 */
@ExtendWith(MockitoExtension.class)
class EmailActionToolServiceTest {

    @Mock
    private EmailDigestService digestService;
    @Mock
    private EmailActionService actionService;
    @Mock
    private EmailReplyService replyService;

    private EmailActionToolService service;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new EmailActionToolService(digestService, actionService, replyService, objectMapper);
    }

    private com.fasterxml.jackson.databind.JsonNode args(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private com.bu.management.vo.EmailDigestResponse digestResponse() {
        com.fasterxml.jackson.databind.node.ArrayNode todos = objectMapper.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode todo = todos.addObject();
        todo.put("messageId", 101);
        todo.put("title", "回复合同");
        todo.put("action", "今天 18:00 前回复");
        return new com.bu.management.vo.EmailDigestResponse(1L, LocalDate.of(2026, 9, 8),
                "SUCCESS", "AI", "deepseek", "总览", 2,
                objectMapper.createArrayNode(), objectMapper.createArrayNode(),
                objectMapper.createArrayNode(), todos, objectMapper.createArrayNode(),
                objectMapper.createArrayNode(), null, "SUCCESS", null, null, 3, 1);
    }

    @Test
    @DisplayName("definitions：下发三个邮件行动工具")
    void definitionsExposeThreeTools() {
        List<AiAgentToolDefinition> defs = service.definitions();

        assertThat(defs).extracting(AiAgentToolDefinition::name)
                .containsExactly("get_my_email_digest", "convert_email_item_to_task", "reply_my_email");
    }

    @Test
    @DisplayName("get_my_email_digest：返回摘要含 emailId 供转化引用")
    void digestToolRendersItems() {
        when(digestService.getResponse(7L, null)).thenReturn(digestResponse());

        AiAgentToolResult result = service.execute(7L, "get_my_email_digest", args(null));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("emailId=101").contains("回复合同");
    }

    @Test
    @DisplayName("convert：缺参数返回 isError，不触发转化")
    void convertWithoutArgsIsError() {
        AiAgentToolResult result = service.execute(7L, "convert_email_item_to_task", args(null));

        assertThat(result.isError()).isTrue();
        verify(actionService, never()).convertToTask(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("convert：TASK 目标走 convertToTask")
    void convertDelegatesToTask() {
        when(actionService.convertToTask(7L, 101L, "TODO", "回复合同", null, 7L))
                .thenReturn(new EmailActionService.ConvertResult("TASK", 88L, "回复合同", true));

        AiAgentToolResult result = service.execute(7L, "convert_email_item_to_task",
                args("{\"emailId\":101,\"itemKind\":\"TODO\",\"itemTitle\":\"回复合同\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("88").contains("已创建任务");
    }

    @Test
    @DisplayName("reply：发送成功返回确认")
    void replySuccess() {
        when(replyService.send(7L, 101L, null, "收到"))
                .thenReturn(new EmailReplyService.ReplyResult(9L, "SENT", null));

        AiAgentToolResult result = service.execute(7L, "reply_my_email",
                args("{\"emailId\":101,\"bodyText\":\"收到\"}"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("发送成功");
    }

    @Test
    @DisplayName("reply：缺 bodyText 返回 isError")
    void replyWithoutBodyIsError() {
        AiAgentToolResult result = service.execute(7L, "reply_my_email", args("{\"emailId\":101}"));

        assertThat(result.isError()).isTrue();
        verify(replyService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("未知工具返回 isError")
    void unknownToolIsError() {
        AiAgentToolResult result = service.execute(7L, "no_such_tool", args(null));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("未知工具");
    }
}
