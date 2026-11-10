package com.bu.management.service;

import com.bu.management.entity.KbChunk;
import com.bu.management.entity.KbDocument;
import com.bu.management.mapper.KbChunkMapper;
import com.bu.management.mapper.KbDocumentMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库入库的两个核心契约：切块口径（多块、不超长、有重叠）与 checksum 幂等（同内容不重建）。
 */
@ExtendWith(MockitoExtension.class)
class KbIngestServiceTest {

    /** 单块硬上限：600 字正文 + 80 字重叠。 */
    private static final int CHUNK_CEILING = KbIngestService.MAX_CHUNK_CHARS + KbIngestService.OVERLAP_CHARS;

    @Mock
    private KbDocumentMapper documentMapper;

    @Mock
    private KbChunkMapper chunkMapper;

    private KbIngestService service;

    @BeforeEach
    void setUp() {
        service = new KbIngestService(documentMapper, chunkMapper);
    }

    @Test
    @DisplayName("长文本被切成多块，每块不超过 600 字 + 重叠，相邻块首尾重叠 80 字")
    void splitsLongContentIntoOverlappingChunks() {
        String sentence = "合拍是面向业务线的经营分析平台，".repeat(40);
        StringBuilder content = new StringBuilder("# 产品说明\n\n");
        for (int i = 1; i <= 3; i++) {
            content.append(sentence).append("\n\n");
        }

        List<KbIngestService.KbChunkDraft> drafts = KbIngestService.split(content.toString());

        assertThat(drafts).hasSizeGreaterThan(1);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.content()).isNotEmpty();
            assertThat(draft.content().length()).isLessThanOrEqualTo(CHUNK_CEILING);
            // 标题行单独成段，只更新 heading，不重复进正文
            assertThat(draft.heading()).isEqualTo("产品说明");
        });
        for (int i = 1; i < drafts.size(); i++) {
            String previous = drafts.get(i - 1).content();
            String overlap = previous.substring(previous.length() - KbIngestService.OVERLAP_CHARS);
            assertThat(drafts.get(i).content()).startsWith(overlap);
        }
    }

    @Test
    @DisplayName("同一 checksum 重复 ingest 不重建：切片数与文档行数都不变")
    void reingestWithSameChecksumSkipsRebuild() {
        String title = "产品能力总览";
        String content = "合拍支持经营分析、项目利润与商机跟进。";
        int expectedChunks = KbIngestService.split(content).size();

        KbDocument[] saved = new KbDocument[1];
        when(documentMapper.findBySourceRef("MANUAL", "doc-1")).thenReturn(null);
        when(documentMapper.insert(any(KbDocument.class))).thenAnswer(invocation -> {
            KbDocument document = invocation.getArgument(0);
            document.setId(7L);
            saved[0] = document;
            return 1;
        });
        when(chunkMapper.insert(any(KbChunk.class))).thenReturn(1);

        KbDocument first = service.ingest(title, content, "MANUAL", "doc-1",
                null, null, null, null, 1L);
        assertThat(first.getId()).isEqualTo(7L);
        verify(chunkMapper, times(expectedChunks)).insert(any(KbChunk.class));

        // 第二次：反查能命中旧文档，checksum 与旧值一致 → 直接返回，不删也不重建
        when(documentMapper.findBySourceRef("MANUAL", "doc-1")).thenReturn(saved[0]);
        KbDocument second = service.ingest(title, content, "MANUAL", "doc-1",
                null, null, null, null, 1L);

        assertThat(second.getId()).isEqualTo(7L);
        verify(chunkMapper, times(expectedChunks)).insert(any(KbChunk.class));
        verify(chunkMapper, never()).deleteByDocumentId(any());
        verify(documentMapper, times(1)).insert(any(KbDocument.class));
    }

    @Test
    @DisplayName("内容变化时先删旧切片再重建")
    void reingestWithChangedChecksumRebuilds() {
        KbDocument existing = new KbDocument();
        existing.setId(9L);
        existing.setChecksum("old-checksum");
        existing.setEnabled(1);

        when(documentMapper.findBySourceRef("MANUAL", "doc-2")).thenReturn(existing);
        when(documentMapper.updateById(any(KbDocument.class))).thenReturn(1);
        when(chunkMapper.insert(any(KbChunk.class))).thenReturn(1);

        int expectedChunks = KbIngestService.split("新正文：经营分析口径已更新。").size();
        service.ingest("标题", "新正文：经营分析口径已更新。", "MANUAL", "doc-2",
                null, null, null, null, 1L);

        verify(chunkMapper).deleteByDocumentId(9L);
        verify(chunkMapper, times(expectedChunks)).insert(any(KbChunk.class));
        verify(documentMapper, never()).insert(any(KbDocument.class));
        verify(documentMapper).updateById(any(KbDocument.class));
    }
}
