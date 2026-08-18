package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.Project;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailProjectGroupingServiceTest {
    @Test
    void acceptsOnlyRealProjectAboveConfidenceThreshold() {
        EmailMessageMapper messageMapper = mock(EmailMessageMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        Project project = project(11L, "CRM项目");
        EmailMessage grouped = message(1L, 7L);
        EmailMessage lowConfidence = message(2L, 7L);
        EmailMessage inventedProject = message(3L, 7L);
        when(messageMapper.selectCount(any())).thenReturn(3L);
        when(messageMapper.selectList(any())).thenReturn(List.of(grouped, lowConfidence, inventedProject));
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        when(client.groupByProjects(any(), any(), org.mockito.ArgumentMatchers.eq(7L))).thenReturn(List.of(
                new EmailProjectAssignment(1L, 11L, 0.92, "标题包含CRM"),
                new EmailProjectAssignment(2L, 11L, 0.40, "相关性不足"),
                new EmailProjectAssignment(3L, 999L, 0.99, "模型臆造项目")));
        when(client.configuredModel()).thenReturn("deepseek-v4-flash");
        Executor direct = Runnable::run;
        EmailProjectGroupingService service = new EmailProjectGroupingService(
                messageMapper, projectMapper, client, direct);

        var result = service.startAsync(7L, false);
        var completed = service.status(7L);

        assertThat(result.status()).isEqualTo("RUNNING");
        assertThat(completed.status()).isEqualTo("SUCCESS");
        assertThat(completed.grouped()).isEqualTo(1);
        assertThat(completed.ungrouped()).isEqualTo(2);
        assertThat(grouped.getProjectId()).isEqualTo(11L);
        assertThat(lowConfidence.getProjectId()).isNull();
        assertThat(inventedProject.getProjectId()).isNull();
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id); project.setName(name); project.setFullPath(name); project.setStatus(1);
        return project;
    }

    private EmailMessage message(Long id, Long ownerId) {
        EmailMessage message = new EmailMessage();
        message.setId(id); message.setOwnerUserId(ownerId); message.setSubject("CRM沟通");
        message.setGroupingStatus("NOT_GROUPED");
        return message;
    }
}
