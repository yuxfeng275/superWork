package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityDescriptor;
import com.bu.management.capability.CapabilityGateway;
import com.bu.management.capability.CapabilityKind;
import com.bu.management.capability.CapabilityRegistry;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 工具入口的单执行路径契约：
 * 13 个能力化工具只走注册表 + 网关；注册表缺失时明确报 CONFIG_ERROR，
 * 不再「悄悄退回旧 switch」；非能力工具才落连接器；夜间白名单与写门禁仍然先生效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiAgentToolServiceTest {

    private static final Long OWNER = 9L;
    private static final String RUN_ID = "run-night";

    @Mock private TaskMapper taskMapper;
    @Mock private RequirementMapper requirementMapper;
    @Mock private WorkLogMapper workLogMapper;
    @Mock private IssueMapper issueMapper;
    @Mock private ConnectorToolService connectorToolService;
    @Mock private GenericConnectorToolService genericConnectorToolService;
    @Mock private EmailActionToolService emailActionToolService;
    @Mock private WeComCliToolService weComCliToolService;
    @Mock private WeeklyReportService weeklyReportService;
    @Mock private JevDecisionService jevDecisionService;
    @Mock private ProjectFactService projectFactService;
    @Mock private ProjectBriefService projectBriefService;
    @Mock private AiArtifactService aiArtifactService;
    @Mock private ProjectFactAccessGuard projectFactAccessGuard;
    @Mock private OpportunityFollowUpService opportunityFollowUpService;
    @Mock private CapabilityGateway capabilityGateway;
    @Mock private CapabilityRegistry capabilityRegistry;

    private AiAgentToolService service;

    @BeforeEach
    void setUp() {
        service = new AiAgentToolService(taskMapper, requirementMapper, workLogMapper, issueMapper,
                new ObjectMapper(), connectorToolService, genericConnectorToolService, emailActionToolService,
                weComCliToolService, weeklyReportService, jevDecisionService, projectFactService,
                projectBriefService, aiArtifactService, projectFactAccessGuard,
                opportunityFollowUpService, capabilityGateway, capabilityRegistry);
        when(jevDecisionService.gateWrite(anyString(), any(), anyString()))
                .thenReturn(new JevDecisionService.WriteGate(true, true, "ok", null));
        service.registerRun(RUN_ID, OWNER);
    }

    @Test
    @DisplayName("能力化工具：注册表命中就走网关，不碰旧实现")
    void capabilityToolGoesThroughGateway() {
        when(capabilityRegistry.find("query_my_tasks"))
                .thenReturn(Optional.of(descriptor("query_my_tasks")));
        when(capabilityGateway.invoke(eq("query_my_tasks"), any(), any(CapabilityContext.class)))
                .thenReturn(CapabilityResult.ok("\"查到 2 条任务\""));

        AiAgentToolResult result = service.execute(RUN_ID, "query_my_tasks", "{}");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("查到 2 条任务");
        verify(taskMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("网关失败：原样把失败文案回给模型")
    void gatewayFailureIsReturnedAsToolError() {
        when(capabilityRegistry.find("project_facts"))
                .thenReturn(Optional.of(descriptor("project_facts")));
        when(capabilityGateway.invoke(eq("project_facts"), any(), any(CapabilityContext.class)))
                .thenReturn(CapabilityResult.fail("GATE_DENIED", "需要先向用户复述"));

        AiAgentToolResult result = service.execute(RUN_ID, "project_facts", "{\"projectId\":1}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("需要先向用户复述");
    }

    @Test
    @DisplayName("能力未注册：明确 CONFIG_ERROR，不再静默退回旧 switch")
    void missingCapabilityIsConfigError() {
        when(capabilityRegistry.find(anyString())).thenReturn(Optional.empty());

        AiAgentToolResult result = service.execute(RUN_ID, "save_artifact", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("CONFIG_ERROR");
        verify(capabilityGateway, never()).invoke(anyString(), any(), any());
    }

    @Test
    @DisplayName("非能力工具：仍然落连接器分发")
    void nonCapabilityToolFallsThroughToConnector() {
        when(capabilityRegistry.find("connector_mail_search")).thenReturn(Optional.empty());
        when(connectorToolService.handles("connector_mail_search")).thenReturn(true);
        when(connectorToolService.execute(eq(OWNER), eq("connector_mail_search"), any()))
                .thenReturn(new AiAgentToolResult("邮件结果", false));

        AiAgentToolResult result = service.execute(RUN_ID, "connector_mail_search", "{}");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("邮件结果");
    }

    @Test
    @DisplayName("夜间无人值守：白名单外的工具直接拒绝")
    void unattendedRunRejectsNonAutomationTool() {
        service.registerRun(RUN_ID, OWNER, null, true);

        AiAgentToolResult result = service.execute(RUN_ID, "search_knowledge", "{\"query\":\"会员通\"}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("夜间无人值守运行不允许调用");
        verify(capabilityGateway, never()).invoke(anyString(), any(), any());
    }

    @Test
    @DisplayName("写门禁拒绝：先于网关返回，工具不执行")
    void writeGateDenialStopsExecution() {
        when(jevDecisionService.gateWrite(anyString(), any(), anyString()))
                .thenReturn(new JevDecisionService.WriteGate(true, false, "save_artifact", "需要先向用户复述"));

        AiAgentToolResult result = service.execute(RUN_ID, "save_artifact", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("复述");
        verify(capabilityGateway, never()).invoke(anyString(), any(), any());
    }

    @Test
    @DisplayName("运行 ID 无效：不查库、直接报错")
    void unknownRunIdIsRejected() {
        AiAgentToolResult result = service.execute("run-unknown", "query_my_tasks", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("无效或已过期");
        verify(capabilityRegistry, never()).find(anyString());
    }

    private CapabilityDescriptor descriptor(String id) {
        return new CapabilityDescriptor(id, CapabilityKind.TOOL, CapabilityRisk.READ,
                id, "test", new ObjectMapper().createObjectNode(), 5000, true, "testBean");
    }
}
