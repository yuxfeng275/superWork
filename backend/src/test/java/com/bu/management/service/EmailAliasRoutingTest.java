package com.bu.management.service;

import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.EmailSenderProjectRule;
import com.bu.management.entity.Project;
import com.bu.management.integration.DeepSeekDigestClient;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.EmailSenderProjectRuleMapper;
import com.bu.management.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 项目邮箱别名路由：+CODE 定向投递，最高优先级，命中即 1.0 */
class EmailAliasRoutingTest {

    @Test
    void aliasBeatsSenderRuleWithConfidenceOne() {
        EmailMessageMapper messageMapper = mock(EmailMessageMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        EmailSenderProjectRuleMapper ruleMapper = mock(EmailSenderProjectRuleMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        EmailMessage message = message(1L);
        message.setSenderAddress("pm@royal.com");
        message.setToAddressesJson("[\"张三 <zhangsan+XM@royal.com>\"]");
        when(messageMapper.selectCount(any())).thenReturn(1L);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(projectMapper.selectList(any()))
                .thenReturn(List.of(project(11L, "销售管理", "XM"), project(22L, "CRM项目", "CRM")));
        EmailSenderProjectRule rule = new EmailSenderProjectRule();
        rule.setSenderPattern("pm@royal.com");
        rule.setProjectId(22L);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(client.groupByProjects(any(), any(), any())).thenReturn(List.of(
                new EmailProjectAssignment(1L, 22L, 0.90, "AI 猜测 CRM")));
        when(client.configuredModel()).thenReturn("deepseek-v4-flash");
        EmailProjectGroupingService service = new EmailProjectGroupingService(
                messageMapper, projectMapper, ruleMapper, client, Runnable::run);

        service.startAsync(7L, false);

        assertThat(message.getProjectId()).isEqualTo(11L);
        assertThat(message.getGroupingConfidence()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(message.getGroupingReason()).contains("别名").contains("+XM");
        assertThat(message.getGroupingAlias()).isEqualTo("+XM");
        assertThat(message.getGroupingStatus()).isEqualTo("GROUPED");
    }

    @Test
    void fallsBackToSenderRuleWithoutAlias() {
        EmailMessageMapper messageMapper = mock(EmailMessageMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        EmailSenderProjectRuleMapper ruleMapper = mock(EmailSenderProjectRuleMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        EmailMessage message = message(1L);
        message.setSenderAddress("pm@royal.com");
        message.setToAddressesJson("[\"ops@royal.com\"]");
        when(messageMapper.selectCount(any())).thenReturn(1L);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(projectMapper.selectList(any()))
                .thenReturn(List.of(project(11L, "销售管理", "XM"), project(22L, "CRM项目", "CRM")));
        EmailSenderProjectRule rule = new EmailSenderProjectRule();
        rule.setSenderPattern("pm@royal.com");
        rule.setProjectId(22L);
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule));
        when(client.groupByProjects(any(), any(), any())).thenReturn(List.of());
        when(client.configuredModel()).thenReturn("deepseek-v4-flash");
        EmailProjectGroupingService service = new EmailProjectGroupingService(
                messageMapper, projectMapper, ruleMapper, client, Runnable::run);

        service.startAsync(7L, false);

        assertThat(message.getProjectId()).isEqualTo(22L);
        assertThat(message.getGroupingReason()).contains("路由规则");
        assertThat(message.getGroupingAlias()).isNull();
    }

    @Test
    void toleratesDisplayNameCaseAndWhitespace() {
        EmailMessageMapper messageMapper = mock(EmailMessageMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        EmailSenderProjectRuleMapper ruleMapper = mock(EmailSenderProjectRuleMapper.class);
        DeepSeekDigestClient client = mock(DeepSeekDigestClient.class);
        EmailMessage message = message(1L);
        message.setSenderAddress("pm@royal.com");
        // 非 JSON 的地址串，走正则兜底
        message.setCcAddressesJson("李四 <lisi+xm@royal.com>; ops@royal.com");
        when(messageMapper.selectCount(any())).thenReturn(1L);
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        // 项目编码带空白、大小写不一致
        when(projectMapper.selectList(any())).thenReturn(List.of(project(11L, "销售管理", " Xm ")));
        when(ruleMapper.selectList(any())).thenReturn(List.of());
        when(client.groupByProjects(any(), any(), any())).thenReturn(List.of());
        when(client.configuredModel()).thenReturn("deepseek-v4-flash");
        EmailProjectGroupingService service = new EmailProjectGroupingService(
                messageMapper, projectMapper, ruleMapper, client, Runnable::run);

        service.startAsync(7L, false);

        assertThat(message.getProjectId()).isEqualTo(11L);
        assertThat(message.getGroupingAlias()).isEqualTo("+XM");
        assertThat(message.getGroupingReason()).contains("+XM");
    }

    private Project project(Long id, String name, String code) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setFullPath(name);
        project.setCode(code);
        project.setStatus(1);
        return project;
    }

    private EmailMessage message(Long id) {
        EmailMessage message = new EmailMessage();
        message.setId(id);
        message.setOwnerUserId(7L);
        message.setSubject("项目沟通");
        message.setGroupingStatus("NOT_GROUPED");
        return message;
    }
}
