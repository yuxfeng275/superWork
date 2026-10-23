package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiProactiveSuggestion;
import com.bu.management.entity.WeeklyReport;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiProactiveSuggestionMapper;
import com.bu.management.mapper.MeetingMapper;
import com.bu.management.mapper.MeetingTodoMapper;
import com.bu.management.vo.BuKeyMatterView;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
 * 主动建议规则：只提「现在该做的一件事」，同周期去重，单条数据源失败不影响其它规则。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProactiveServiceTest {

    @Mock private AiProactiveSuggestionMapper suggestionMapper;
    @Mock private WeeklyReportService weeklyReportService;
    @Mock private BuKeyMatterService keyMatterService;
    @Mock private MeetingMapper meetingMapper;
    @Mock private MeetingTodoMapper meetingTodoMapper;
    @Mock private EmailDigestService emailDigestService;

    private AiProactiveService service;
    private final LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);

    @BeforeEach
    void setUp() {
        service = new AiProactiveService(suggestionMapper, weeklyReportService, keyMatterService,
                meetingMapper, meetingTodoMapper, emailDigestService);
        when(suggestionMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(weeklyReportService.history(any(Integer.class))).thenReturn(List.of());
        when(keyMatterService.meeting(any())).thenReturn(List.of());
        when(meetingMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    @Test
    void proposesWeeklyDraftWhenThisWeekHasNoReport() {
        service.scan(7L);

        AiProactiveSuggestion row = singleInsert();
        assertThat(row.getKind()).isEqualTo("weekly_report");
        assertThat(row.getDedupeKey()).isEqualTo("周报:" + monday);
        assertThat(row.getTitle()).isEqualTo("本周周报还没有草稿");
        assertThat(row.getTargetPath()).isEqualTo("/weekly-report?week=" + monday);
        assertThat(row.getScore()).isEqualTo(90);
        assertThat(row.getStatus()).isEqualTo(AiProactiveSuggestion.STATUS_NEW);
        assertThat(row.getPrompt()).contains("weekly_report_facts");
    }

    @Test
    void surfacesFailedGenerationWithHigherScore() {
        WeeklyReport failed = new WeeklyReport();
        failed.setWeekStartDate(monday);
        failed.setStatus(WeeklyReport.STATUS_GENERATION_FAILED);
        failed.setGenerationError("所有摘要模型均失败：deepseek/deepseek-flash HTTP 402");
        when(weeklyReportService.history(any(Integer.class))).thenReturn(List.of(failed));

        service.scan(7L);

        AiProactiveSuggestion row = singleInsert();
        assertThat(row.getTitle()).isEqualTo("本周周报上次生成失败");
        assertThat(row.getBody()).contains("HTTP 402");
        assertThat(row.getScore()).isEqualTo(96);
    }

    @Test
    void dismissesStaleWeeklySuggestionOnceDraftExists() {
        WeeklyReport draft = new WeeklyReport();
        draft.setWeekStartDate(monday);
        draft.setStatus(WeeklyReport.STATUS_DRAFT);
        when(weeklyReportService.history(any(Integer.class))).thenReturn(List.of(draft));
        AiProactiveSuggestion stale = new AiProactiveSuggestion();
        stale.setId(31L);
        stale.setUserId(7L);
        stale.setDedupeKey("周报失败:" + monday);
        stale.setStatus(AiProactiveSuggestion.STATUS_NEW);
        when(suggestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(stale));

        service.scan(7L);

        assertThat(stale.getStatus()).isEqualTo(AiProactiveSuggestion.STATUS_DISMISSED);
        verify(suggestionMapper).updateById(stale);
        verify(suggestionMapper, never()).insert(any(AiProactiveSuggestion.class));
    }

    @Test
    void proposesMatterProgressOnlyForMyUnupdatedMatters() {
        when(keyMatterService.meeting(any())).thenReturn(List.of(
                matter(1L, 7L, "皇家会员体系", false, "推进中"),
                matter(2L, 7L, "飞鹤接口改造", false, "有风险"),
                matter(3L, 7L, "已更新的", true, "推进中"),
                matter(4L, 8L, "别人的事项", false, "推进中"),
                matter(5L, 7L, "已完成事项", false, "已完成")));

        service.scan(7L);

        List<AiProactiveSuggestion> saved = insertedRows();
        assertThat(saved).hasSize(2);
        AiProactiveSuggestion matter = saved.stream()
                .filter(row -> row.getKind().equals("key_matter_week")).findFirst().orElseThrow();
        assertThat(matter.getTargetPath()).isEqualTo("/key-matters?matterId=1");
        assertThat(matter.getTitle()).isEqualTo("2 个大事儿本周还没更新进展");
        assertThat(matter.getBody()).contains("皇家会员体系").contains("飞鹤接口改造");
        assertThat(matter.getScore()).isEqualTo(72);
    }

    @Test
    void skipsRuleAlreadyProposedInSamePeriod() {
        when(suggestionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service.scan(7L);

        verify(suggestionMapper, never()).insert(any(AiProactiveSuggestion.class));
    }

    @Test
    void brokenSourceDoesNotBlockOtherRules() {
        when(keyMatterService.meeting(any())).thenThrow(new IllegalStateException("db down"));

        service.scan(7L);

        AiProactiveSuggestion row = singleInsert();
        assertThat(row.getKind()).isEqualTo("weekly_report");
    }

    @Test
    void rejectsSuggestionOwnedBySomeoneElse() {
        AiProactiveSuggestion row = new AiProactiveSuggestion();
        row.setId(11L);
        row.setUserId(8L);
        when(suggestionMapper.selectById(11L)).thenReturn(row);

        assertThatThrownBy(() -> service.accept(7L, 11L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(suggestionMapper, never()).updateById(any(AiProactiveSuggestion.class));
    }

    @Test
    void dismissMarksTerminalState() {
        AiProactiveSuggestion row = new AiProactiveSuggestion();
        row.setId(12L);
        row.setUserId(7L);
        row.setStatus(AiProactiveSuggestion.STATUS_NEW);
        when(suggestionMapper.selectById(12L)).thenReturn(row);

        service.dismiss(7L, 12L);

        assertThat(row.getStatus()).isEqualTo(AiProactiveSuggestion.STATUS_DISMISSED);
        verify(suggestionMapper).updateById(row);
    }

    private AiProactiveSuggestion singleInsert() {
        List<AiProactiveSuggestion> rows = insertedRows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<AiProactiveSuggestion> insertedRows() {
        ArgumentCaptor<AiProactiveSuggestion> captor =
                ArgumentCaptor.forClass(AiProactiveSuggestion.class);
        verify(suggestionMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private BuKeyMatterView matter(Long id, Long ownerId, String title, boolean updated,
                                   String status) {
        return matter(id, ownerId, title, updated, status, false);
    }

    private BuKeyMatterView matter(Long id, Long ownerId, String title, boolean updated,
                                   String status, boolean overdue) {
        BuKeyMatterView view = new BuKeyMatterView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setTitle(title);
        view.setCurrentWeekUpdated(updated);
        view.setStatus(status);
        view.setOverdue(overdue);
        return view;
    }

    @Test
    void reportsPublishedReportWhoseYuqueTocIsNotMounted() {
        WeeklyReport published = new WeeklyReport();
        published.setWeekStartDate(monday.minusDays(7));
        published.setStatus(WeeklyReport.STATUS_PUBLISHED);
        published.setYuqueTocStatus("NOT_FOUND");
        when(weeklyReportService.history(any(Integer.class))).thenReturn(List.of(published));

        service.scan(7L);

        AiProactiveSuggestion toc = insertedRows().stream()
                .filter(row -> row.getKind().equals("weekly_toc")).findFirst().orElseThrow();
        assertThat(toc.getTitle()).isEqualTo("周会纪要没挂到语雀部门会议目录");
        assertThat(toc.getBody()).contains("NOT_FOUND");
        assertThat(toc.getTargetPath()).contains("anchor=publish");
        assertThat(toc.getScore()).isEqualTo(80);
    }

    @Test
    void reportsOverdueMattersWithHigherPriority() {
        when(keyMatterService.meeting(any())).thenReturn(List.of(
                matter(9L, 7L, "逾期项目 A", true, "有风险", true),
                matter(10L, 7L, "正常项目 B", true, "推进中", false)));

        service.scan(7L);

        AiProactiveSuggestion overdue = insertedRows().stream()
                .filter(row -> row.getKind().equals("key_matter_overdue")).findFirst().orElseThrow();
        assertThat(overdue.getTitle()).isEqualTo("1 个大事儿已逾期");
        assertThat(overdue.getTargetPath()).isEqualTo("/key-matters?matterId=9");
        assertThat(overdue.getScore()).isEqualTo(86);
    }
}
