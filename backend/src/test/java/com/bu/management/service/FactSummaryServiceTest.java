package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.EmailMessage;
import com.bu.management.entity.ProjectFact;
import com.bu.management.mapper.EmailMessageMapper;
import java.util.ArrayList;
import java.util.List;
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

/**
 * 汇总口径：未分类邮件是用户级（owner + project_id IS NULL），无正文文件是项目级内存算，
 * 本机隔离数恒为 null（服务端不掌握，前端显示占位）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FactSummaryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 11L;

    @Mock private ProjectFactService factService;
    @Mock private ProjectFactPolicy factPolicy;
    @Mock private EmailMessageMapper emailMessageMapper;

    private FactSummaryService service;

    /** LambdaQueryWrapper 解列名要有表信息，纯单元测试里自己初始化一次。 */
    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                EmailMessage.class);
    }

    @BeforeEach
    void setUp() {
        service = new FactSummaryService(factService, factPolicy, emailMessageMapper);
        when(factService.qualityOf(any())).thenReturn(true);
    }

    @Test
    @DisplayName("未分类邮件按 owner_user_id + project_id IS NULL 统计，不再按项目查")
    void ungroupedUsesOwnerScopedUncategorizedQuery() {
        givenFacts(List.of(fact(ProjectFactService.SOURCE_NOTE, "补充说明", "正文")));
        when(emailMessageMapper.selectCount(any())).thenReturn(4L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.ungrouped()).isEqualTo(4L);
        Wrapper<EmailMessage> wrapper = capturedCountWrapper();
        String where = wrapper.getSqlSegment();
        // 单测里没有全局下划线配置，列名渲染成属性名（同 ProjectFactServiceTest 的口径）
        assertThat(where).contains("ownerUserId =", "projectId IS NULL", "groupingStatus =");
        assertThat(where).doesNotContain("projectId =");
        assertThat(capturedParams(wrapper)).contains(USER_ID, "UNGROUPED");
        // 传的是当前用户的项目？绝不是——projectId 不该进这把查询的参数
        assertThat(capturedParams(wrapper)).doesNotContain(PROJECT_ID);
    }

    @Test
    @DisplayName("ungroupedFiles 只算无正文或摘要过短的本机投递文件")
    void ungroupedFilesCountsOnlyThinFileUploads() {
        ProjectFact noBodyShortDigest = fact(ProjectFactService.SOURCE_FILE_UPLOAD, "a.pdf", null);
        noBodyShortDigest.setDigest("a.pdf");
        ProjectFact noBodyLongDigest = fact(ProjectFactService.SOURCE_FILE_UPLOAD, "b.xlsx", null);
        noBodyLongDigest.setDigest("b".repeat(80));
        ProjectFact bodyShortDigest = fact(ProjectFactService.SOURCE_FILE_UPLOAD, "c.docx", "正文在这里");
        bodyShortDigest.setDigest("c");
        ProjectFact bodyLongDigest = fact(ProjectFactService.SOURCE_FILE_UPLOAD, "d.md", "正文在这里");
        bodyLongDigest.setDigest("d".repeat(80));
        // 不是投递文件：摘要再短也不算
        ProjectFact noteShort = fact(ProjectFactService.SOURCE_NOTE, "随手一句", null);
        noteShort.setDigest("随手一句");
        givenFacts(List.of(noBodyShortDigest, noBodyLongDigest, bodyShortDigest, bodyLongDigest,
                noteShort));
        when(emailMessageMapper.selectCount(any())).thenReturn(2L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        // 无正文（d 是长摘要但没正文，同样算）+ 有正文但摘要过短；正文齐全的不算
        assertThat(summary.ungroupedFiles()).isEqualTo(3L);
        assertThat(summary.total()).isEqualTo(5L);
        // 内存算，不为这个数字额外查库
        verify(emailMessageMapper, times(1)).selectCount(any());
    }

    @Test
    @DisplayName("isolated 恒为 null：本机隔离计数服务端不掌握")
    void isolatedAlwaysNull() {
        givenFacts(List.of());
        when(emailMessageMapper.selectCount(any())).thenReturn(0L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.isolated()).isNull();
        assertThat(summary.ungrouped()).isZero();
        assertThat(summary.ungroupedFiles()).isZero();
        assertThat(summary.thisWeekZero()).isTrue();
    }

    @Test
    @DisplayName("未分类统计失败返回 null（不是 0），其余口径照常")
    void ungroupedNullWhenMapperFails() {
        givenFacts(List.of(fact(ProjectFactService.SOURCE_NOTE, "补充说明", "正文")));
        when(emailMessageMapper.selectCount(any())).thenThrow(new IllegalStateException("库挂了"));

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.ungrouped()).isNull();
        assertThat(summary.total()).isEqualTo(1L);
        assertThat(summary.last24h()).isEqualTo(1L);
    }

    @Test
    @DisplayName("没有可见事实时也不打邮件表以外的地方，bySource 为空、总量为 0")
    void emptyProjectStillQueriesUngrouped() {
        givenFacts(List.of());
        when(emailMessageMapper.selectCount(any())).thenReturn(5L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.bySource()).isEmpty();
        assertThat(summary.total()).isZero();
        assertThat(summary.ungrouped()).isEqualTo(5L);
        verify(factService, never()).ingestProject(any(), any());
    }

    @Test
    @DisplayName("501 条分两页累加：total / lowQuality / ungroupedFiles 都是全量，truncated=false")
    void sumsAcrossPages() {
        List<ProjectFact> facts = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            if (i < 251) {
                ProjectFact thin = fact(ProjectFactService.SOURCE_FILE_UPLOAD, "投递-" + i + ".pdf", null);
                thin.setDigest("投递-" + i + ".pdf");
                facts.add(thin);
            } else {
                facts.add(fact(ProjectFactService.SOURCE_NOTE, "补充说明-" + i, "正文在这里"));
            }
        }
        givenFacts(facts);
        // qualityOf 返回 true 是「合格」：没正文的 251 条投递文件判低质（返回 false）
        when(factService.qualityOf(any())).thenAnswer(inv ->
                inv.getArgument(0, ProjectFact.class).getBody() != null);
        when(emailMessageMapper.selectCount(any())).thenReturn(6L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.total()).isEqualTo(501L);
        assertThat(summary.bySource())
                .containsEntry(ProjectFactService.SOURCE_FILE_UPLOAD, 251L)
                .containsEntry(ProjectFactService.SOURCE_NOTE, 250L);
        assertThat(summary.lowQuality()).isEqualTo(251L);
        assertThat(summary.ungroupedFiles()).isEqualTo(251L);
        assertThat(summary.truncated()).isFalse();
        // 分页参数要对上：第一页 offset 0，第二页 offset 500，第二页不满就不再翻
        verify(factService).query(any(), any(), any(), eq(500), eq(0));
        verify(factService).query(any(), any(), any(), eq(500), eq(500));
        verify(factService, times(2)).query(any(), any(), any(), eq(500), anyInt());
    }

    @Test
    @DisplayName("翻满 20 页还有数据时标 truncated=true，数字只是下限")
    void truncatedWhenPagesExhausted() {
        // 每页都取满：limit 多少就回多少条，20 页翻完仍然有余量
        when(factService.query(any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> fullPage(inv.getArgument(3)));
        when(factPolicy.visible(any(), any(), anyList())).thenAnswer(inv -> inv.getArgument(2));
        when(emailMessageMapper.selectCount(any())).thenReturn(0L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        assertThat(summary.truncated()).isTrue();
        assertThat(summary.total()).isEqualTo(10_000L);
        // 正好翻 20 页，最后一页 offset = 19 * 500
        verify(factService, times(20)).query(any(), any(), any(), eq(500), anyInt());
        verify(factService).query(any(), any(), any(), eq(500), eq(9_500));
    }

    @Test
    @DisplayName("本周新增但 happenedAt 在去年的事实照计入本周，不误报本周零新增")
    void thisWeekUsesCreatedAtNotHappenedAt() {
        ProjectFact backfilled = fact(ProjectFactService.SOURCE_NOTE, "补记去年的事", "正文在这里");
        backfilled.setHappenedAt(java.time.LocalDateTime.now().minusYears(1));
        givenFacts(List.of(backfilled));
        when(emailMessageMapper.selectCount(any())).thenReturn(0L);

        FactSummaryService.FactsSummary summary = service.summary(USER_ID, PROJECT_ID);

        // 按 created_at 算「新增」：本周不算 0，24 小时内的新增也照算
        assertThat(summary.thisWeekZero()).isFalse();
        assertThat(summary.last24h()).isEqualTo(1L);
        assertThat(summary.total()).isEqualTo(1L);
        assertThat(summary.truncated()).isFalse();
    }

    /** 让可见事实口径走真实实现的替身：query 分页出多少就放行多少。 */
    private void givenFacts(List<ProjectFact> facts) {
        when(factService.query(any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(inv -> slice(facts, inv.getArgument(3), inv.getArgument(4)));
        when(factPolicy.visible(any(), any(), anyList())).thenAnswer(inv -> inv.getArgument(2));
    }

    /** 按 limit/offset 切页，等价于 SQL 的 limit/offset。 */
    private List<ProjectFact> slice(List<ProjectFact> facts, int limit, int offset) {
        if (limit <= 0 || offset >= facts.size()) {
            return List.of();
        }
        return facts.subList(offset, Math.min(offset + limit, facts.size()));
    }

    /** 永远取满一页的替身。 */
    private List<ProjectFact> fullPage(int limit) {
        List<ProjectFact> page = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            page.add(fact(ProjectFactService.SOURCE_NOTE, "补充说明", "正文在这里"));
        }
        return page;
    }

    private Wrapper<EmailMessage> capturedCountWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<EmailMessage>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(emailMessageMapper).selectCount(captor.capture());
        return captor.getValue();
    }

    /** wrapper 绑定的实参（LambdaQueryWrapper 只在 AbstractWrapper 上暴露参数表）。 */
    private java.util.Collection<Object> capturedParams(Wrapper<EmailMessage> wrapper) {
        assertThat(wrapper)
                .isInstanceOf(com.baomidou.mybatisplus.core.conditions.AbstractWrapper.class);
        return ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                .getParamNameValuePairs().values();
    }

    private ProjectFact fact(String sourceType, String title, String body) {
        ProjectFact fact = new ProjectFact();
        fact.setProjectId(PROJECT_ID);
        fact.setSourceType(sourceType);
        fact.setTitle(title);
        fact.setBody(body);
        fact.setDigest(title);
        fact.setVisibility(ProjectFactService.VISIBILITY_PROJECT);
        fact.setCreatedAt(java.time.LocalDateTime.now());
        return fact;
    }
}
