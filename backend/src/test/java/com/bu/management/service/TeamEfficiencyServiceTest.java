package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.bu.management.entity.AiArtifact;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.Task;
import com.bu.management.entity.User;
import com.bu.management.mapper.AiArtifactMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.UserMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 团队效能的口径契约：投递率只算近 7 天、低质事实要计入、成员负载按未完成任务与逾期拆分，
 * 以及空数据不出现除零。
 */
@ExtendWith(MockitoExtension.class)
class TeamEfficiencyServiceTest {

    @Mock private ProjectFactMapper factMapper;
    @Mock private AiArtifactMapper artifactMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private UserMapper userMapper;

    private TeamEfficiencyService service;

    @BeforeEach
    void setUp() {
        service = new TeamEfficiencyService(factMapper, artifactMapper, taskMapper, projectMapper, userMapper);
    }

    @Test
    @DisplayName("投递率 / 低质 / 成员负载 / 待评审都按同一批数据算")
    void aggregatesTeamView() {
        LocalDateTime now = LocalDateTime.now();
        ProjectFact good = fact(1L, 7L, now.minusDays(1));
        good.setBody("有正文");
        good.setDigest("这是一条足够长的摘要，可以用于出方案，包含背景、结论与下一步动作");
        ProjectFact low = fact(1L, 7L, now.minusDays(30));
        low.setBody("");
        when(factMapper.selectList(any())).thenReturn(List.of(good, low));

        AiArtifact approved = artifact(1L, 7L, "APPROVED");
        AiArtifact pending = artifact(1L, 7L, "IN_REVIEW");
        when(artifactMapper.selectList(any())).thenReturn(List.of(approved), List.of(pending));

        Task open = task(7L, "DOING", LocalDate.now().minusDays(2));
        Task notOverdue = task(7L, "DOING", LocalDate.now().plusDays(3));
        when(taskMapper.selectList(any())).thenReturn(List.of(open, notOverdue));

        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, "皇家项目"), project(2L, "飞鹤")));
        User user = new User();
        user.setId(7L);
        user.setRealName("张三");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user));

        TeamEfficiencyService.Report report = service.overview(30);

        assertThat(report.days()).isEqualTo(30);
        assertThat(report.activeProjects()).isEqualTo(2);
        // 只有 project 1 在近 7 天有事实
        assertThat(report.deliveredProjects()).isEqualTo(1);
        assertThat(report.deliveryRate()).isEqualTo(0.5d);
        assertThat(report.totalFacts()).isEqualTo(2); // 30 天窗口内的 good + low
        assertThat(report.lowQualityFacts()).isEqualTo(1);
        assertThat(report.pendingArtifacts()).isEqualTo(1);

        TeamEfficiencyService.ProjectHealth first = report.projects().get(0);
        assertThat(first.projectId()).isEqualTo(1L);
        assertThat(first.facts()).isEqualTo(2);
        assertThat(first.contributors()).isEqualTo(1);
        assertThat(first.lowQuality()).isEqualTo(1);
        assertThat(first.approved()).isEqualTo(1);
        assertThat(first.pendingArtifacts()).isEqualTo(1);

        TeamEfficiencyService.MemberHealth member = report.members().stream()
                .filter(row -> row.userId().equals(7L)).findFirst().orElseThrow();
        assertThat(member.name()).isEqualTo("张三");
        assertThat(member.facts()).isEqualTo(2);
        assertThat(member.openTasks()).isEqualTo(2);
        assertThat(member.overdueTasks()).isEqualTo(1);
        assertThat(member.pendingArtifacts()).isEqualTo(1);
    }

    @Test
    @DisplayName("空库：投递率为 0 而不是 NaN，列表为空")
    void emptyDataIsZero() {
        when(factMapper.selectList(any())).thenReturn(List.of());
        when(artifactMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(taskMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of());

        TeamEfficiencyService.Report report = service.overview(null);

        assertThat(report.days()).isEqualTo(30);
        assertThat(report.deliveryRate()).isZero();
        assertThat(report.projects()).isEmpty();
        assertThat(report.members()).isEmpty();
    }

    private static ProjectFact fact(Long projectId, Long actorId, LocalDateTime createdAt) {
        ProjectFact fact = new ProjectFact();
        fact.setProjectId(projectId);
        fact.setActorId(actorId);
        fact.setCreatedAt(createdAt);
        fact.setBody("正文");
        fact.setDigest("这是一条足够长的摘要，可以用于出方案，包含背景、结论与下一步动作");
        return fact;
    }

    private static AiArtifact artifact(Long projectId, Long ownerId, String status) {
        AiArtifact artifact = new AiArtifact();
        artifact.setProjectId(projectId);
        artifact.setOwnerUserId(ownerId);
        artifact.setStatus(status);
        artifact.setCreatedAt(LocalDateTime.now().minusDays(1));
        return artifact;
    }

    private static Task task(Long assigneeId, String status, LocalDate endDate) {
        Task task = new Task();
        task.setAssigneeId(assigneeId);
        task.setStatus(status);
        task.setEndDate(endDate);
        return task;
    }

    private static Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setStatus(1);
        return project;
    }
}
