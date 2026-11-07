package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiArtifact;
import com.bu.management.mapper.IssueMapper;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import com.bu.management.vo.AiAgentToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 夜间自动化 save_artifact：建完草稿立刻自己送审，产出人不该再卡在「人要手动点提交」那一步。
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

    private AiAgentToolService service;

    @BeforeEach
    void setUp() {
        service = new AiAgentToolService(taskMapper, requirementMapper, workLogMapper, issueMapper,
                new ObjectMapper(), connectorToolService, genericConnectorToolService, emailActionToolService,
                weComCliToolService, weeklyReportService, jevDecisionService, projectFactService,
                projectBriefService, aiArtifactService);
        when(jevDecisionService.gateWrite(anyString(), any(), anyString()))
                .thenReturn(new JevDecisionService.WriteGate(true, true, "save_artifact", null));
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
