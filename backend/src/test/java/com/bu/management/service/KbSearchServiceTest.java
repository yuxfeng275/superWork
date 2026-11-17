package com.bu.management.service;

import com.bu.management.mapper.KbChunkMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索的三个核心契约：过短问句直接短路（ngram 切不出 token）、
 * 片段拼接必须带编号 + 文档标题且不超预算，以及检索故障不能被吞成「无命中」。
 */
@ExtendWith(MockitoExtension.class)
class KbSearchServiceTest {

    @Mock
    private KbChunkMapper chunkMapper;

    private KbSearchService service;

    @BeforeEach
    void setUp() {
        service = new KbSearchService(chunkMapper);
    }

    @Test
    @DisplayName("问句去空白后不足 2 字直接返回空，且不查库")
    void tooShortQueryReturnsEmpty() {
        assertThat(service.search("合", 1L, KbSearchService.Scope.none(), 5)).isEmpty();
        assertThat(service.search("  ", 1L, KbSearchService.Scope.none(), 5)).isEmpty();
        assertThat(service.search(null, 1L, KbSearchService.Scope.none(), 5)).isEmpty();
        verifyNoInteractions(chunkMapper);
    }

    @Test
    @DisplayName("两条命中 → 片段含编号与文档标题，且不超过 maxChars")
    void buildSnippetContainsTitlesAndStaysWithinBudget() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1L, "documentId", 11L, "heading", "产品能力",
                        "content", "合拍支持经营分析、项目利润与商机跟进三张主表。",
                        "documentTitle", "合拍白皮书", "score", 3.5d),
                Map.of("id", 2L, "documentId", 12L, "heading", "交付口径",
                        "content", "项目利润按验收月份确认收入。",
                        "documentTitle", "利润口径说明", "score", 1.2d));
        when(chunkMapper.search(eq("合拍 白皮书"), eq(9L), isNull(), isNull(), eq(5))).thenReturn(rows);

        List<KbHit> hits = service.search(" 合拍 白皮书 ", 9L, KbSearchService.Scope.none(), 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).documentTitle()).isEqualTo("合拍白皮书");
        assertThat(hits.get(0).score()).isEqualTo(3.5d);

        String snippet = service.buildContextSnippet(hits, 300);
        assertThat(snippet).contains("【知识库片段】")
                .contains("[1] 《合拍白皮书》- 产品能力")
                .contains("[2] 《利润口径说明》- 交付口径")
                .contains("合拍支持经营分析");
        assertThat(snippet.length()).isLessThanOrEqualTo(300);

        // 预算收紧时只截断，不超长
        String tight = service.buildContextSnippet(hits, 60);
        assertThat(tight.length()).isLessThanOrEqualTo(60);
        assertThat(tight).startsWith("【知识库片段】");
    }

    @Test
    @DisplayName("检索失败抛 KB_SEARCH_FAILED，而不是伪装成空召回")
    void searchFailureThrowsWithStableCode() {
        when(chunkMapper.search(eq("合拍"), eq(9L), isNull(), isNull(), eq(5)))
                .thenThrow(new IllegalStateException("ngram 索引缺失"));
        assertThatThrownBy(() -> service.search("合拍", 9L, KbSearchService.Scope.none(), 5))
                .isInstanceOf(KbSearchException.class)
                .hasMessageContaining("知识库检索暂时不可用")
                .extracting(e -> ((KbSearchException) e).code())
                .isEqualTo(KbSearchException.CODE);
    }

    @Test
    @DisplayName("项目与业务线各走各的参数：项目 12 不会命中业务线 12 的文档")
    void projectAndBusinessLineScopesArePassedSeparately() {
        when(chunkMapper.search(eq("会员通"), eq(9L), eq(12L), isNull(), eq(5)))
                .thenReturn(List.of());

        assertThat(service.search("会员通", 9L, KbSearchService.Scope.project(12L), 5)).isEmpty();

        org.mockito.Mockito.verify(chunkMapper)
                .search("会员通", 9L, 12L, null, 5);
    }
}
