package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.bu.management.capability.CapabilityRisk;
import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import com.bu.management.plugin.tasks.QueryMyTasksCapability;
import com.bu.management.vo.AiAgentToolResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AI 工具入口的两件事：save_artifact 建完草稿自己送审；吃模型给的 projectId 的读写工具
 * 必须先判项目准入（非成员换 ID 读别人项目、往别人项目写制品都拒）。
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

    /** LambdaQueryWrapper 解列名要有表信息，纯单元测试里自己初始化一次（带下划线转驼峰，跟生产一致）。 */
    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, ""),
                Requirement.class);
    }

    @BeforeEach
    void setUp() {
        service = new AiAgentToolService(taskMapper, requirementMapper, workLogMapper, issueMapper,
                new ObjectMapper(), connectorToolService, genericConnectorToolService, emailActionToolService,
                weComCliToolService, weeklyReportService, jevDecisionService, projectFactService,
                projectBriefService, aiArtifactService, projectFactAccessGuard,
                opportunityFollowUpService, capabilityGateway, capabilityRegistry);
        // 默认：注册表里什么都没注册 → 全部走旧 switch（旧用例的行为完全不变）
        when(capabilityRegistry.find(anyString())).thenReturn(Optional.empty());
        when(jevDecisionService.gateWrite(anyString(), any(), anyString()))
                .thenReturn(new JevDecisionService.WriteGate(true, true, "save_artifact", null));
        // 默认：本人是项目成员/负责人（准入通过）；拒绝用例再单独打桩
        when(projectFactAccessGuard.canRead(anyLong(), anyLong())).thenReturn(true);
        when(projectFactAccessGuard.canWrite(anyLong(), anyLong())).thenReturn(true);
        service.registerRun(RUN_ID, OWNER);
    }

    @Test
    @DisplayName("save_artifact：创建成功后自动送审，工具回报 IN_REVIEW")
    void saveArtifactAutoSubmits() {
        when(aiArtifactService.create(anyLong(), anyLong(), any())).thenReturn(artifact("DRAFT"));
        when(aiArtifactService.submit(OWNER, 5L)).thenReturn(artifact("IN_REVIEW"));

        AiAgentToolResult result = service.execute(RUN_ID, "save_artifact", argsJson());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("IN_REVIEW");
        verify(aiArtifactService).submit(OWNER, 5L);
    }

    @Test
    @DisplayName("自动送审失败：工具照常返回，制品留在 DRAFT 等人工提交")
    void saveArtifactKeepsDraftWhenAutoSubmitFails() {
        when(aiArtifactService.create(anyLong(), anyLong(), any())).thenReturn(artifact("DRAFT"));
        when(aiArtifactService.submit(OWNER, 5L))
                .thenThrow(new IllegalArgumentException("当前状态（IN_REVIEW）不允许提交评审"));

        AiAgentToolResult result = service.execute(RUN_ID, "save_artifact", argsJson());

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("DRAFT");
    }

    @Test
    @DisplayName("非成员调 project_facts：明确返回 error + 无权，而不是空列表")
    void projectFactsDeniedForNonMember() {
        when(projectFactAccessGuard.canRead(OWNER, 11L)).thenReturn(false);

        AiAgentToolResult result = service.execute(RUN_ID, "project_facts",
                "{\"projectId\":11,\"sinceDays\":3}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("无权");
        assertThat(result.content()).contains("项目成员");
        verify(projectFactService, never()).queryForAgent(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("成员调 project_facts：正常返回事实 JSON（含出处）")
    void projectFactsReturnsVisibleFacts() {
        ProjectFact fact = new ProjectFact();
        fact.setId(12L);
        fact.setProjectId(11L);
        fact.setSourceType(ProjectFactService.SOURCE_MEETING);
        fact.setSourceRef("meeting:7");
        fact.setTitle("皇家项目周会");
        fact.setDigest("皇家项目周会 · 排期对齐");
        fact.setVisibility(ProjectFactService.VISIBILITY_COMPANY);
        when(projectFactService.queryForAgent(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of(fact));

        AiAgentToolResult result = service.execute(RUN_ID, "project_facts",
                "{\"projectId\":11,\"sinceDays\":3}");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("meeting:7").contains("皇家项目周会");
    }

    @Test
    @DisplayName("准入通过但没有可见事实：正常空 facts + 一句提示")
    void projectFactsEmptyGivesHint() {
        when(projectFactService.queryForAgent(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of());

        AiAgentToolResult result = service.execute(RUN_ID, "project_facts",
                "{\"projectId\":11}");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("\"facts\":[]").contains("hint");
    }

    @Test
    @DisplayName("非成员调 save_artifact：拒绝写入，不落制品")
    void saveArtifactDeniedForNonMember() {
        when(projectFactAccessGuard.canWrite(OWNER, 11L)).thenReturn(false);

        AiAgentToolResult result = service.execute(RUN_ID, "save_artifact", argsJson());

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("无权");
        verify(aiArtifactService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("非成员调 save_daily_brief 带 projectId：拒绝写入")
    void saveDailyBriefDeniedForNonMember() {
        when(projectFactAccessGuard.canWrite(OWNER, 11L)).thenReturn(false);

        AiAgentToolResult result = service.execute(RUN_ID, "save_daily_brief",
                "{\"projectId\":11,\"content\":\"{\\\"items\\\":[]}\"}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("无权");
        verify(projectBriefService, never()).save(anyLong(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("夜间自动化（userId=项目负责人）仍能正常取数并落制品")
    void automationRunOfProjectManagerStillWorks() {
        String nightRun = "run-autoloop";
        service.registerRun(nightRun, OWNER, "给皇家项目准备排期", true);
        ProjectFact fact = new ProjectFact();
        fact.setId(12L);
        fact.setSourceType(ProjectFactService.SOURCE_MEETING);
        fact.setSourceRef("meeting:7");
        fact.setTitle("皇家项目周会");
        when(projectFactService.queryForAgent(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of(fact));
        when(aiArtifactService.create(anyLong(), anyLong(), any())).thenReturn(artifact("DRAFT"));
        when(aiArtifactService.submit(OWNER, 5L)).thenReturn(artifact("IN_REVIEW"));

        AiAgentToolResult facts = service.execute(nightRun, "project_facts", "{\"projectId\":11}");
        AiAgentToolResult saved = service.execute(nightRun, "save_artifact", argsJson());

        assertThat(facts.isError()).isFalse();
        assertThat(facts.content()).contains("皇家项目周会");
        assertThat(saved.isError()).isFalse();
        verify(projectFactAccessGuard).canRead(OWNER, 11L);
        verify(projectFactAccessGuard).canWrite(OWNER, 11L);
    }

    @Test
    @DisplayName("query_my_requirements：查询按 creatorId=当前用户过滤（否则任意用户能读全系统的需求）")
    void queryMyRequirementsScopedToCreator() {
        AiAgentToolResult result = service.execute(RUN_ID, "query_my_requirements", "{\"limit\":5}");

        assertThat(result.isError()).isFalse();
        Wrapper<Requirement> wrapper = capturedRequirementWrapper();
        // 先把 SQL 拼出来（MP 的参数表是懒填充的），再断言条件里只绑了当前用户的 ID：
        // 没有 status 时不能退化成全量，口径就是 creator_id。
        String sqlSegment = wrapper.getCustomSqlSegment();
        assertThat(sqlSegment).contains("creator_id").containsIgnoringCase("order by");
        assertThat(capturedParams(wrapper)).containsExactly(OWNER);
    }

    @Test
    @DisplayName("query_my_requirements：userId 为空时不查库、返回空结果（不退化成全量）")
    void queryMyRequirementsWithoutUserReturnsEmpty() throws Exception {
        Method method = AiAgentToolService.class.getDeclaredMethod(
                "queryMyRequirements", Long.class, JsonNode.class);
        method.setAccessible(true);

        Object[] args = {null, new ObjectMapper().createObjectNode()};
        AiAgentToolResult result = (AiAgentToolResult) method.invoke(service, args);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("未返回任何需求");
        verify(requirementMapper, never()).selectList(any());
    }

    // ==================== 注册表优先 + 旧 switch 兜底 ====================

    @Test
    @DisplayName("双跑对比：query_my_tasks 网关开 / 关两次返回文本逐字相等")
    void queryMyTasksGatewayMatchesLegacySwitch() throws Exception {
        Task task = new Task();
        task.setTitle("写周报");
        task.setTaskType("DAILY");
        task.setStatus("进行中");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        String args = "{\"status\":\"进行中\",\"limit\":5}";
        AiAgentToolResult viaSwitch = service.execute(RUN_ID, "query_my_tasks", args);

        // 打开注册表优先：find 命中 + invoke 落到真实插件（同一个 taskMapper 桩）
        when(capabilityRegistry.find("query_my_tasks"))
                .thenReturn(Optional.of(descriptor("query_my_tasks")));
        when(capabilityGateway.invoke(eq("query_my_tasks"), any(), any())).thenAnswer(invocation ->
                new QueryMyTasksCapability(taskMapper).execute(
                        invocation.getArgument(1), invocation.getArgument(2)));

        AiAgentToolResult viaGateway = service.execute(RUN_ID, "query_my_tasks", args);

        assertThat(viaGateway.isError()).isFalse();
        assertThat(viaSwitch.isError()).isFalse();
        assertThat(viaGateway.content()).isEqualTo(viaSwitch.content());
        assertThat(viaGateway.content()).contains("写周报");
    }

    @Test
    @DisplayName("未知工具：注册表里没有，仍然返回原来的「未知工具」文案")
    void unknownToolReturnsLegacyMessage() {
        when(genericConnectorToolService.execute(anyString(), any()))
                .thenReturn(new AiAgentToolResult("未知工具：bogus_tool", true));

        AiAgentToolResult result = service.execute(RUN_ID, "bogus_tool", "{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("未知工具：bogus_tool");
        verify(capabilityGateway, never()).invoke(anyString(), any(), any());
    }

    @Test
    @DisplayName("开关关闭：能力已注册也全部走旧 switch，网关一次都不调")
    void gatewayDisabledFallsBackToSwitch() throws Exception {
        ReflectionTestUtils.setField(service, "gatewayToolsEnabled", false);
        when(capabilityRegistry.find("query_my_tasks"))
                .thenReturn(Optional.of(descriptor("query_my_tasks")));
        when(taskMapper.selectList(any())).thenReturn(List.of());

        AiAgentToolResult result = service.execute(RUN_ID, "query_my_tasks", "{}");

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("当前用户（ID=9）暂无符合条件的任务记录");
        verify(capabilityGateway, never()).invoke(anyString(), any(), any());
    }

    /** 一个最小可用的能力描述符（服务层只关心 find 是否命中）。 */
    private CapabilityDescriptor descriptor(String id) throws Exception {
        return new CapabilityDescriptor(id, CapabilityKind.TOOL, CapabilityRisk.READ, id, "",
                new ObjectMapper().readTree("{}"), 15000, true, "bean-" + id);
    }

    private Wrapper<Requirement> capturedRequirementWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Requirement>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(requirementMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /** wrapper 绑定的实参（LambdaQueryWrapper 只在 AbstractWrapper 上暴露参数表）。 */
    private java.util.Collection<Object> capturedParams(Wrapper<Requirement> wrapper) {
        assertThat(wrapper)
                .isInstanceOf(com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class);
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                .getParamNameValuePairs().values();
    }

    private String argsJson() {
        return "{\"projectId\":11,\"title\":\"夜间排期方案\",\"kind\":\"PLAN\","
                + "\"content\":\"方案正文\",\"evidence\":\"[12,15]\",\"taskId\":\"T-1\"}";
    }

    private AiArtifact artifact(String status) {
        AiArtifact artifact = new AiArtifact();
        artifact.setId(5L);
        artifact.setProjectId(11L);
        artifact.setOwnerUserId(OWNER);
        artifact.setKind(AiArtifactService.KIND_PLAN);
        artifact.setTitle("夜间排期方案");
        artifact.setContent("方案正文");
        artifact.setEvidence("[12,15]");
        artifact.setStatus(status);
        return artifact;
    }
}
