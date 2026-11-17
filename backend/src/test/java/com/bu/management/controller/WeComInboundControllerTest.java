package com.bu.management.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import com.bu.management.service.ProjectFactService;
import java.time.LocalDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 企微内部群入站端点的三条硬约束：
 * <ul>
 *   <li>同一 payload 重传只落一条（source_ref 幂等，回读同一条事实）；</li>
 *   <li>external == true 是 400 且一行都不写（外部联系人单聊属会话存档范围）；</li>
 *   <li>对不上花名册的发言人 actorId 记 null，正文带 [未知成员] 前缀。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeComInboundControllerTest {

    private static final Long PROJECT_ID = 11L;
    private static final Long ACTOR_ID = 42L;
    private static final String SOURCE_TYPE = ProjectFactService.SOURCE_CHAT_EXPORT;

    @Mock private ProjectFactService factService;
    @Mock private UserMapper userMapper;

    private WeComInboundController controller;
    private ProjectContextProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ProjectContextProperties();
        properties.setToken("");
        controller = new WeComInboundController(factService, userMapper, properties);
        when(factService.appendManual(anyLong(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(factService.find(anyString(), anyString())).thenReturn(new ProjectFact());
    }

    private static WeComInboundController.WeComHookRequest payload() {
        return new WeComInboundController.WeComHookRequest(
                "张野", "zhangye", "皇家 PMS 实施群", "2026-11-06 14:22",
                "周五前要一版排期", "PROJECT", false);
    }

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }

    @Test
    @DisplayName("同一 payload 投两次：source_ref 相同，回读同一条事实（库内只新增 1 条）")
    void samePayloadTwiceIsIdempotent() {
        ProjectFact fact = new ProjectFact();
        fact.setId(9001L);
        when(factService.find(eq(SOURCE_TYPE), anyString())).thenReturn(fact);

        ResponseEntity<?> first = controller.hook(PROJECT_ID, payload(), req());
        ResponseEntity<?> second = controller.hook(PROJECT_ID, payload(), req());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> refs = ArgumentCaptor.forClass(String.class);
        verify(factService, org.mockito.Mockito.times(2))
                .appendManual(eq(PROJECT_ID), eq(SOURCE_TYPE), refs.capture(), any(), any(), any(), any(), any());

        List<String> captured = refs.getAllValues();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0)).isEqualTo(captured.get(1)).startsWith("wecom:");
        assertThat(((com.bu.management.vo.Result<?>) second.getBody()).getData()).isSameAs(fact);
    }

    @Test
    @DisplayName("external=true：400 且不落库")
    void externalChatIsRejectedAndNotPersisted() {
        WeComInboundController.WeComHookRequest external = new WeComInboundController.WeComHookRequest(
                "客户-王工", "wmXXXX", "客户单聊", "2026-11-06 14:22", "周五前要一版排期", "PROJECT", true);

        ResponseEntity<?> response = controller.hook(PROJECT_ID, external, req());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        com.bu.management.vo.Result<?> body = (com.bu.management.vo.Result<?>) response.getBody();
        assertThat(body.getMessage()).contains("会话内容存档").contains("msgaudit");
        verifyNoInteractions(factService);
    }

    @Test
    @DisplayName("未知发言人：actorId 记 null，正文带 [未知成员] 前缀")
    void unknownSenderFallsBackToNullActor() {
        when(userMapper.selectList(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.hook(PROJECT_ID, payload(), req());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<Long> actor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(factService).appendManual(eq(PROJECT_ID), eq(SOURCE_TYPE), anyString(), actor.capture(),
                any(LocalDateTime.class), title.capture(), body.capture(), eq("PROJECT"));

        assertThat(actor.getValue()).isNull();
        assertThat(body.getValue()).startsWith("2026-11-06 14:22 [未知成员] ").contains("张野: 周五前要一版排期");
        assertThat(title.getValue()).isEqualTo("皇家 PMS 实施群 · 张野");
    }

    @Test
    @DisplayName("senderKey 优先命中花名册（忽略大小写），actorId 落到 user.id")
    void senderKeyResolvesActor() {
        User user = new User();
        user.setId(ACTOR_ID);
        user.setUsername("ZhangYe");
        user.setRealName("张野");
        when(userMapper.selectList(any())).thenReturn(List.of(user));

        controller.hook(PROJECT_ID, payload(), req());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(factService).appendManual(eq(PROJECT_ID), eq(SOURCE_TYPE), anyString(), eq(ACTOR_ID),
                any(LocalDateTime.class), anyString(), body.capture(), eq("PROJECT"));
        assertThat(body.getValue()).doesNotContain("[未知成员]");
    }

    @Test
    @DisplayName("text 为空：400，不落库")
    void blankTextIs400() {
        WeComInboundController.WeComHookRequest blank = new WeComInboundController.WeComHookRequest(
                "张野", "zhangye", "皇家 PMS 实施群", "2026-11-06 14:22", "  ", "PROJECT", false);

        ResponseEntity<?> response = controller.hook(PROJECT_ID, blank, req());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(factService);
    }

    @Test
    @DisplayName("配了令牌但没带：401，不落库")
    void missingTokenIs401() {
        properties.setToken("s3cret");

        ResponseEntity<?> response = controller.hook(PROJECT_ID, payload(), req());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(factService);
    }
}
