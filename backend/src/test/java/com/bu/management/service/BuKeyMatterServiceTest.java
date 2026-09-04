package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.dto.BuKeyMatterWeeklyUpdateRequest;
import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.BuKeyMatterWeeklyUpdate;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.mapper.BuKeyMatterWeeklyUpdateMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.BuKeyMatterView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuKeyMatterServiceTest {

    @Mock private BuKeyMatterMapper matterMapper;
    @Mock private BuKeyMatterWeeklyUpdateMapper weeklyUpdateMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private BuKeyMatterParticipantMapper participantMapper;
    @Mock private BuKeyMatterAccessService accessService;

    private BuKeyMatterService service;

    @BeforeEach
    void setUp() {
        service = new BuKeyMatterService(
                matterMapper, weeklyUpdateMapper, userMapper, projectMapper,
                participantMapper, accessService);
    }

    @Test
    void createPersistsValidOwnerProjectAndAppliesDefaults() {
        User owner = new User();
        owner.setId(7L);
        owner.setStatus(1);
        Project project = new Project();
        project.setId(3L);
        when(userMapper.selectById(7L)).thenReturn(owner);
        when(projectMapper.selectById(3L)).thenReturn(project);
        when(matterMapper.insert(any(BuKeyMatter.class))).thenAnswer(invocation -> {
            invocation.<BuKeyMatter>getArgument(0).setId(11L);
            return 1;
        });
        BuKeyMatterRequest request = request();

        BuKeyMatter created = service.create(request, 16L, "admin");

        assertThat(created.getId()).isEqualTo(11L);
        assertThat(created.getPriority()).isEqualTo("P1");
        assertThat(created.getStatus()).isEqualTo("未开始");
        assertThat(created.getProgress()).isZero();
        assertThat(created.getCreatedBy()).isEqualTo(16L);
        assertThat(created.getOwnerId()).isEqualTo(7L);
        assertThat(created.getProjectId()).isEqualTo(3L);
        verify(matterMapper).insert(created);
    }

    @Test
    void createRejectsInvalidDates() {
        BuKeyMatterRequest request = request();
        request.setPlannedCompletionDate(request.getStartDate().minusDays(1));

        assertThatThrownBy(() -> service.create(request, 16L, "admin"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("计划完成日期不能早于开始日期");
    }

    @Test
    void updateDelegatesEditAuthorizationBeforeWrite() {
        BuKeyMatter matter = matter(11L, "P1", "推进中", 70,
                LocalDate.of(2026, 8, 28));
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        BuKeyMatterRequest request = request();
        doThrow(new com.bu.management.exception.ForbiddenOperationException("仅可编辑本人负责的大事儿"))
                .when(accessService).requireEdit(matter, 30L, "creator");

        assertThatThrownBy(() -> service.update(11L, request, 30L, "creator"))
                .isInstanceOf(com.bu.management.exception.ForbiddenOperationException.class)
                .hasMessage("仅可编辑本人负责的大事儿");
        verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
    }

    @Test
    void createDelegatesOwnerAuthorizationBeforeInsert() {
        BuKeyMatterRequest request = request();
        doThrow(new com.bu.management.exception.ForbiddenOperationException("仅可创建本人负责的大事儿"))
                .when(accessService).requireCreate(7L, 16L, "creator");

        assertThatThrownBy(() -> service.create(request, 16L, "creator"))
                .isInstanceOf(com.bu.management.exception.ForbiddenOperationException.class)
                .hasMessage("仅可创建本人负责的大事儿");
        verify(matterMapper, never()).insert(any(BuKeyMatter.class));
    }

    @Test
    void updateSetsAndClearsCompletedAt() {
        BuKeyMatter matter = matter(11L, "P1", "推进中", 70,
                LocalDate.of(2026, 8, 28));
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(userMapper.selectById(7L)).thenReturn(activeUser(7L, "石家乐"));
        when(projectMapper.selectById(3L)).thenReturn(project(3L, "皇家项目"));
        BuKeyMatterRequest completed = request();
        completed.setStatus("已完成");
        completed.setProgress(80);

        BuKeyMatter updated = service.update(11L, completed, 16L, "yufeng");

        assertThat(updated.getStatus()).isEqualTo("已完成");
        assertThat(updated.getProgress()).isEqualTo(100);
        assertThat(updated.getCompletedAt()).isNotNull();

        BuKeyMatterRequest reopened = request();
        reopened.setStatus("推进中");
        reopened.setProgress(90);
        service.update(11L, reopened, 16L, "yufeng");

        assertThat(matter.getCompletedAt()).isNull();
    }

    @Test
    void weeklyUpdateUpsertsSameWeekAndSynchronizesLatestMatterState() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter matter = matter(11L, "P1", "推进中", 40,
                LocalDate.of(2026, 8, 28));
        BuKeyMatterWeeklyUpdate existing = weekly(21L, 11L, week, "推进中", 40);
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        BuKeyMatterWeeklyUpdateRequest request = weeklyRequest("有风险", 60, "核心链路联调完成");

        BuKeyMatterWeeklyUpdate saved = service.upsertWeeklyUpdate(11L, week, request, 16L, "yufeng");

        assertThat(saved.getId()).isEqualTo(21L);
        assertThat(saved.getProgress()).isEqualTo(60);
        assertThat(saved.getProgressSummary()).isEqualTo("核心链路联调完成");
        assertThat(matter.getStatus()).isEqualTo("有风险");
        assertThat(matter.getProgress()).isEqualTo(60);
        verify(weeklyUpdateMapper).updateById(existing);
        verify(matterMapper).updateById(matter);
    }

    @Test
    void weeklyUpsertAuthorizesCurrentUserAfterLockingMatter() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter matter = matter(11L, "P1", "推进中", 40,
                LocalDate.of(2026, 8, 28));
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.upsertWeeklyUpdate(11L, week, weeklyRequest("推进中", 40, "进展正常"), 7L, "shijiale");

        verify(accessService).requireFeedback(matter, 7L, "shijiale");
    }

    @Test
    void editingOlderWeekDoesNotRollBackCurrentMatterState() {
        LocalDate olderWeek = LocalDate.of(2026, 7, 27);
        BuKeyMatter matter = matter(11L, "P1", "推进中", 80,
                LocalDate.of(2026, 8, 28));
        BuKeyMatterWeeklyUpdate latest = weekly(
                22L, 11L, LocalDate.of(2026, 8, 3), "推进中", 80);
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(latest));

        service.upsertWeeklyUpdate(
                11L, olderWeek, weeklyRequest("有风险", 50, "补录历史进展"), 16L, "yufeng");

        assertThat(matter.getStatus()).isEqualTo("推进中");
        assertThat(matter.getProgress()).isEqualTo(80);
        verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
    }

    @Test
    void completedMatterRejectsCreatingWeeklyUpdate() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter matter = matter(11L, "P1", "已完成", 100,
                LocalDate.of(2026, 8, 28));
        matter.setCompletedAt(LocalDateTime.of(2026, 8, 2, 10, 0));
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        // Blank summary would fail request validation; the guard must reject first.
        BuKeyMatterWeeklyUpdateRequest request = weeklyRequest("已完成", 100, "");

        assertThatThrownBy(() -> service.upsertWeeklyUpdate(11L, week, request, 16L, "yufeng"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("已完成事项无需新增周进展");

        verify(weeklyUpdateMapper, never()).insert(any(BuKeyMatterWeeklyUpdate.class));
        verify(weeklyUpdateMapper, never()).updateById(any(BuKeyMatterWeeklyUpdate.class));
        verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
    }

    @Test
    void completedMatterAllowsExistingWeeklyCorrectionWithoutReopeningMatter() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter matter = matter(11L, "P1", "已完成", 100,
                LocalDate.of(2026, 8, 28));
        LocalDateTime completedAt = LocalDateTime.of(2026, 8, 2, 10, 0);
        matter.setCompletedAt(completedAt);
        BuKeyMatterWeeklyUpdate existing = weekly(21L, 11L, week, "已完成", 100);
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        BuKeyMatterWeeklyUpdate saved = service.upsertWeeklyUpdate(
                11L, week, weeklyRequest("推进中", 90, "补充交付说明"), 16L, "yufeng");

        assertThat(saved.getId()).isEqualTo(21L);
        assertThat(saved.getStatus()).isEqualTo("推进中");
        assertThat(saved.getProgress()).isEqualTo(90);
        assertThat(saved.getProgressSummary()).isEqualTo("补充交付说明");
        assertThat(matter.getStatus()).isEqualTo("已完成");
        assertThat(matter.getProgress()).isEqualTo(100);
        assertThat(matter.getCompletedAt()).isEqualTo(completedAt);
        verify(weeklyUpdateMapper).updateById(existing);
        verify(matterMapper, never()).updateById(any(BuKeyMatter.class));
    }

    @Test
    void reopenedMatterCanCreateWeeklyUpdate() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter matter = matter(11L, "P1", "推进中", 40,
                LocalDate.of(2026, 8, 28));
        when(matterMapper.selectByIdForUpdate(11L)).thenReturn(matter);
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(weeklyUpdateMapper.insert(any(BuKeyMatterWeeklyUpdate.class))).thenAnswer(invocation -> {
            invocation.<BuKeyMatterWeeklyUpdate>getArgument(0).setId(31L);
            return 1;
        });

        BuKeyMatterWeeklyUpdate saved = service.upsertWeeklyUpdate(
                11L, week, weeklyRequest("有风险", 60, "核心链路联调完成"), 16L, "yufeng");

        assertThat(saved.getId()).isEqualTo(31L);
        assertThat(saved.getKeyMatterId()).isEqualTo(11L);
        assertThat(saved.getProgress()).isEqualTo(60);
        assertThat(matter.getStatus()).isEqualTo("有风险");
        assertThat(matter.getProgress()).isEqualTo(60);
        verify(weeklyUpdateMapper).insert(any(BuKeyMatterWeeklyUpdate.class));
        verify(matterMapper).updateById(matter);
    }

    @Test
    void meetingIncludesOpenMattersAndOnlyThisWeeksCompletedMatters() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter open = matter(1L, "P1", "推进中", 60, LocalDate.of(2026, 8, 20));
        BuKeyMatter completedThisWeek = matter(
                2L, "P1", "已完成", 100, LocalDate.of(2026, 8, 5));
        completedThisWeek.setCompletedAt(LocalDateTime.of(2026, 8, 4, 15, 0));
        BuKeyMatter completedEarlier = matter(
                3L, "P1", "已完成", 100, LocalDate.of(2026, 7, 31));
        completedEarlier.setCompletedAt(LocalDateTime.of(2026, 7, 31, 15, 0));
        when(matterMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(open, completedThisWeek, completedEarlier));
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<BuKeyMatterView> result = service.meeting(week);

        assertThat(result).extracting(BuKeyMatterView::getId).containsExactly(1L, 2L);
    }

    @Test
    void meetingSortsBlockedAndRiskItemsWithinPriority() {
        LocalDate week = LocalDate.of(2026, 8, 3);
        BuKeyMatter normal = matter(1L, "P1", "推进中", 60, LocalDate.of(2026, 8, 20));
        BuKeyMatter risk = matter(2L, "P1", "有风险", 50, LocalDate.of(2026, 8, 19));
        BuKeyMatter blocked = matter(3L, "P1", "已阻塞", 40, LocalDate.of(2026, 8, 18));
        BuKeyMatter critical = matter(4L, "P0", "推进中", 30, LocalDate.of(2026, 8, 30));
        when(matterMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(normal, risk, blocked, critical));
        when(weeklyUpdateMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<BuKeyMatterView> result = service.meeting(week);

        assertThat(result).extracting(BuKeyMatterView::getId)
                .containsExactly(4L, 3L, 2L, 1L);
    }

    private BuKeyMatterRequest request() {
        BuKeyMatterRequest request = new BuKeyMatterRequest();
        request.setTitle("皇家会员体系二期上线");
        request.setDescription("完成核心会员链路切换");
        request.setProjectId(3L);
        request.setOwnerId(7L);
        request.setStartDate(LocalDate.of(2026, 8, 3));
        request.setPlannedCompletionDate(LocalDate.of(2026, 8, 28));
        return request;
    }

    private BuKeyMatter matter(Long id, String priority, String status,
                               int progress, LocalDate plannedCompletionDate) {
        BuKeyMatter matter = new BuKeyMatter();
        matter.setId(id);
        matter.setTitle("事项" + id);
        matter.setOwnerId(7L);
        matter.setProjectId(3L);
        matter.setPriority(priority);
        matter.setStatus(status);
        matter.setProgress(progress);
        matter.setStartDate(LocalDate.of(2026, 8, 1));
        matter.setPlannedCompletionDate(plannedCompletionDate);
        matter.setSortOrder(0);
        matter.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        matter.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        return matter;
    }

    private BuKeyMatterWeeklyUpdate weekly(Long id, Long matterId, LocalDate week,
                                            String status, int progress) {
        BuKeyMatterWeeklyUpdate update = new BuKeyMatterWeeklyUpdate();
        update.setId(id);
        update.setKeyMatterId(matterId);
        update.setWeekStartDate(week);
        update.setStatus(status);
        update.setProgress(progress);
        update.setProgressSummary("周进展");
        return update;
    }

    private BuKeyMatterWeeklyUpdateRequest weeklyRequest(
            String status, int progress, String summary) {
        BuKeyMatterWeeklyUpdateRequest request = new BuKeyMatterWeeklyUpdateRequest();
        request.setStatus(status);
        request.setProgress(progress);
        request.setProgressSummary(summary);
        request.setIssues("资源排期存在冲突");
        request.setNextWeekPlan("完成灰度验证");
        request.setSupportNeeded("确认上线窗口");
        return request;
    }

    private User activeUser(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setRealName(name);
        user.setStatus(1);
        return user;
    }

    private Project project(Long id, String name) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        return project;
    }
}
