package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.config.ProjectContextProperties;
import com.bu.management.entity.Meeting;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.EmailMessageMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.ProjectFactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.YunxiaoWorkitemCacheMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 项目事实层：幂等键稳定、同一源对象只更新不重复、会议沿用上传人可见。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFactServiceTest {

    @Mock private ProjectFactMapper factMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private MeetingMapper meetingMapper;
    @Mock private EmailMessageMapper emailMessageMapper;
    @Mock private YunxiaoWorkitemCacheMapper workitemCacheMapper;
    @Mock private BuKeyMatterMapper keyMatterMapper;
    @Mock private ProjectFactAccessGuard projectFactAccessGuard;

    private ProjectFactService service;

    @BeforeEach
    void setUp() {
        ProjectContextProperties properties = new ProjectContextProperties();
        properties.setMaxSourceRows(50);
        service = new ProjectFactService(factMapper, projectMapper, meetingMapper,
                emailMessageMapper, workitemCacheMapper, keyMatterMapper, properties,
                new ProjectFactPolicy(projectFactAccessGuard));
    }

    @Test
    void dedupeHashIsStableAndDistinct() {
        assertThat(ProjectFactService.dedupeHash("MEETING", "meeting:1"))
                .hasSize(64)
                .isEqualTo(ProjectFactService.dedupeHash("MEETING", "meeting:1"))
                .isNotEqualTo(ProjectFactService.dedupeHash("MEETING", "meeting:2"))
                .isNotEqualTo(ProjectFactService.dedupeHash("EMAIL", "meeting:1"));
    }

    @Test
    void digestKeepsTitleAndTruncates() {
        assertThat(ProjectFactService.digestOf("周会", "讨论了排期")).isEqualTo("周会 · 讨论了排期");
        assertThat(ProjectFactService.digestOf("", "只有正文")).isEqualTo("只有正文");
        assertThat(ProjectFactService.digestOf("标题", "")).isEqualTo("标题");
    }

    @Test
    void ingestMeetingInsertsOnceThenUpdates() {
        Meeting meeting = new Meeting();
        meeting.setId(7L);
        meeting.setProjectId(11L);
        meeting.setOwnerUserId(3L);
        meeting.setTitle("皇家项目周会");
        meeting.setMeetingDate(LocalDate.of(2026, 10, 23));
        meeting.setSummaryJson("{\"summary\":\"排期对齐\"}");
        meeting.setUpdatedAt(LocalDateTime.now());
        when(meetingMapper.selectList(any())).thenReturn(List.of(meeting));
        when(emailMessageMapper.selectList(any())).thenReturn(List.of());
        when(workitemCacheMapper.selectList(any())).thenReturn(List.of());
        when(keyMatterMapper.selectList(any())).thenReturn(List.of());
        when(factMapper.selectOne(any())).thenReturn(null);
        when(factMapper.insert(any(ProjectFact.class))).thenReturn(1);

        int changed = service.ingestProject(11L, LocalDateTime.now().minusDays(1));

        assertThat(changed).isEqualTo(1);
        ArgumentCaptor<ProjectFact> captor = ArgumentCaptor.forClass(ProjectFact.class);
        verify(factMapper).insert(captor.capture());
        ProjectFact inserted = captor.getValue();
        assertThat(inserted.getSourceType()).isEqualTo(ProjectFactService.SOURCE_MEETING);
        assertThat(inserted.getSourceRef()).isEqualTo("meeting:7");
        // 会议 v1 口径：只有上传人可见
        assertThat(inserted.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_OWNER);
        assertThat(inserted.getDedupeHash()).isEqualTo(ProjectFactService.dedupeHash("MEETING", "meeting:7"));
        assertThat(inserted.getDigest()).contains("皇家项目周会");
    }

    @Test
    void ingestSameMeetingTwiceUpdatesInsteadOfInserting() {
        Meeting meeting = new Meeting();
        meeting.setId(7L);
        meeting.setProjectId(11L);
        meeting.setTitle("皇家项目周会");
        meeting.setMeetingDate(LocalDate.of(2026, 10, 23));
        meeting.setUpdatedAt(LocalDateTime.now());
        when(meetingMapper.selectList(any())).thenReturn(List.of(meeting));
        when(emailMessageMapper.selectList(any())).thenReturn(List.of());
        when(workitemCacheMapper.selectList(any())).thenReturn(List.of());
        when(keyMatterMapper.selectList(any())).thenReturn(List.of());
        ProjectFact existing = new ProjectFact();
        existing.setId(99L);
        when(factMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.ingestProject(11L, LocalDateTime.now().minusDays(1));

        verify(factMapper, never()).insert(any(ProjectFact.class));
        assertThat(existing.getTitle()).isEqualTo("皇家项目周会");
    }

    @Test
    void secondSubmitterCannotStealActorOrVisibility() {
        when(factMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(factMapper.insert(any(ProjectFact.class))).thenReturn(1);

        // userA 投递同一份文件：项目可见
        int first = service.appendManual(11L, ProjectFactService.SOURCE_FILE_UPLOAD, "sha256:abc", 3L,
                LocalDateTime.now(), "报价单.xlsx", "第一版内容", ProjectFactService.VISIBILITY_PROJECT);
        assertThat(first).isEqualTo(1);
        ArgumentCaptor<ProjectFact> captor = ArgumentCaptor.forClass(ProjectFact.class);
        verify(factMapper).insert(captor.capture());
        ProjectFact stored = captor.getValue();
        assertThat(stored.getActorId()).isEqualTo(3L);
        assertThat(stored.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_PROJECT);

        // userB 重传同一 sha256：命中已有事实，行数不变，归属与可见性仍属于 A
        when(factMapper.selectOne(any(Wrapper.class))).thenReturn(stored);
        when(factMapper.updateById(any(ProjectFact.class))).thenReturn(1);
        int second = service.appendManual(11L, ProjectFactService.SOURCE_FILE_UPLOAD, "sha256:abc", 4L,
                LocalDateTime.now().plusMinutes(5), "抄来的报价单.xlsx", "第二版内容",
                ProjectFactService.VISIBILITY_OWNER);

        assertThat(second).isEqualTo(1);
        verify(factMapper, times(1)).insert(any(ProjectFact.class));
        assertThat(stored.getActorId()).isEqualTo(3L);
        assertThat(stored.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_PROJECT);
        // 内容仍然可以刷新（幂等不新增行）
        assertThat(stored.getTitle()).isEqualTo("抄来的报价单.xlsx");
        assertThat(stored.getBody()).isEqualTo("第二版内容");
    }

    @Test
    void sameSubmitterCanUpdateOwnFact() {
        when(factMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(factMapper.insert(any(ProjectFact.class))).thenReturn(1);
        service.appendManual(11L, ProjectFactService.SOURCE_CHAT_EXPORT, "chat:42", 3L,
                LocalDateTime.now(), "客户群聊记录", "旧内容", ProjectFactService.VISIBILITY_OWNER);
        ArgumentCaptor<ProjectFact> captor = ArgumentCaptor.forClass(ProjectFact.class);
        verify(factMapper).insert(captor.capture());
        ProjectFact stored = captor.getValue();

        when(factMapper.selectOne(any(Wrapper.class))).thenReturn(stored);
        when(factMapper.updateById(any(ProjectFact.class))).thenReturn(1);
        int updated = service.appendManual(11L, ProjectFactService.SOURCE_CHAT_EXPORT, "chat:42", 3L,
                LocalDateTime.now().plusMinutes(5), "客户群聊记录（补全）", "新内容",
                ProjectFactService.VISIBILITY_PROJECT);

        assertThat(updated).isEqualTo(1);
        verify(factMapper, times(1)).insert(any(ProjectFact.class));
        assertThat(stored.getActorId()).isEqualTo(3L);
        assertThat(stored.getTitle()).isEqualTo("客户群聊记录（补全）");
        assertThat(stored.getBody()).isEqualTo("新内容");
        assertThat(stored.getVisibility()).isEqualTo(ProjectFactService.VISIBILITY_PROJECT);
    }

    @Test
    void ingestWithoutProjectIdIsNoop() {
        assertThat(service.ingestProject(null, LocalDateTime.now())).isZero();
        verify(meetingMapper, never()).selectList(any());
    }

    @Test
    void queryForAgentReturnsEmptyForNonMemberWithoutHittingDb() {
        when(projectFactAccessGuard.canRead(7L, 11L)).thenReturn(false);

        assertThat(service.queryForAgent(7L, 11L, null, null, 10)).isEmpty();
        // 准入不通过就不查库（不靠 SQL 条件兼做准入）
        verify(factMapper, never()).selectList(any());
    }

    @Test
    void queryForAgentFiltersByPolicyForMember() {
        when(projectFactAccessGuard.canRead(5L, 11L)).thenReturn(true);
        ProjectFact projectVisible = fact(1L, ProjectFactService.VISIBILITY_PROJECT, 3L);
        ProjectFact othersOwner = fact(2L, ProjectFactService.VISIBILITY_OWNER, 3L);
        ProjectFact company = fact(3L, ProjectFactService.VISIBILITY_COMPANY, 3L);
        when(factMapper.selectList(any())).thenReturn(List.of(projectVisible, othersOwner, company));
        // 成员但非管理者：PRIVATE_COMPANY 按 canWrite 一档判（这里给 true，验证策略真的生效）
        when(projectFactAccessGuard.canWrite(5L, 11L)).thenReturn(true);

        assertThat(service.queryForAgent(5L, 11L, List.of(ProjectFactService.SOURCE_MEETING),
                LocalDateTime.now().minusDays(3), 10))
                .extracting(ProjectFact::getId).containsExactly(1L, 3L);
    }

    @Test
    void queryForAgentHidesPrivateCompanyWhenWriteDenied() {
        when(projectFactAccessGuard.canRead(5L, 11L)).thenReturn(true);
        when(projectFactAccessGuard.canWrite(5L, 11L)).thenReturn(false);
        ProjectFact projectVisible = fact(1L, ProjectFactService.VISIBILITY_PROJECT, 3L);
        ProjectFact company = fact(3L, ProjectFactService.VISIBILITY_COMPANY, 3L);
        when(factMapper.selectList(any())).thenReturn(List.of(projectVisible, company));

        assertThat(service.queryForAgent(5L, 11L, null, null, 10))
                .extracting(ProjectFact::getId).containsExactly(1L);
    }

    private ProjectFact fact(Long id, String visibility, Long actorId) {
        ProjectFact fact = new ProjectFact();
        fact.setId(id);
        fact.setProjectId(11L);
        fact.setVisibility(visibility);
        fact.setActorId(actorId);
        fact.setTitle("事实" + id);
        return fact;
    }
}
