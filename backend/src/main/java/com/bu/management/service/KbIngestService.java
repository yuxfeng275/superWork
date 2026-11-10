package com.bu.management.service;

import com.bu.management.entity.KbChunk;
import com.bu.management.entity.KbDocument;
import com.bu.management.mapper.KbChunkMapper;
import com.bu.management.mapper.KbDocumentMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库入库：把一篇长文切成可检索的块。
 *
 * <p>三步：
 * <ol>
 *   <li>按 (sourceType, sourceRef) 反查旧文档，checksum 相同直接返回——内容没变就不重建；</li>
 *   <li>切块：先按空行分段，段内超 600 字再按句号 / 分号切，相邻块重叠 80 字；</li>
 *   <li>事务内写 document + chunks（变更时先删旧切片）。</li>
 * </ol>
 *
 * <p>刻意不做的事：不调外部 embedding、不猜标题层级、不合并超短段——
 * 第一步先把「能用 ngram 搜出来」跑通，块质量靠真实语料再调。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestService {

    /** 单块正文字数上限：再长会让 ngram 相关度被稀释，检索排序反而变差。 */
    static final int MAX_CHUNK_CHARS = 600;

    /** 相邻块重叠字数：不留重叠时跨块的句子两边都召不全。 */
    static final int OVERLAP_CHARS = 80;

    /** 空行分段：连续空行只切一刀。 */
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n");

    /** 句末标点（保留标点本身，切完拼回去还是原句）。 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。；;！!？?\\n])");

    /** 中文序号小标题：「一、」「二、」…… */
    private static final Pattern HAN_HEADING = Pattern.compile("^[一二三四五六七八九十]+、.*");

    private final KbDocumentMapper documentMapper;

    private final KbChunkMapper chunkMapper;

    /**
     * 入库（幂等）。
     *
     * @param title          标题，必填
     * @param content        正文，必填
     * @param sourceType     来源类型，缺省 MANUAL
     * @param sourceRef      来源内唯一标识，可为空（为空则每次入库都是新文档）
     * @param scopeType      可见范围，缺省 COMPANY
     * @param scopeId        范围对象 ID，OWNER 时为本人用户 ID
     * @param businessLine   业务线名称，纯展示
     * @param tags           逗号分隔标签，纯展示
     * @param operatorUserId 入库人用户 ID
     * @return 落库后的文档（未重建时返回的是旧文档）
     */
    @Transactional
    public KbDocument ingest(String title, String content, String sourceType, String sourceRef,
                             String scopeType, Long scopeId, String businessLine, String tags,
                             Long operatorUserId) {
        if (!StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("知识库文档的标题与正文都不能为空");
        }
        String normalizedTitle = title.strip();
        String normalizedContent = normalizeNewlines(content);
        String type = StringUtils.hasText(sourceType) ? sourceType.strip().toUpperCase() : KbDocument.SOURCE_MANUAL;
        String ref = StringUtils.hasText(sourceRef) ? sourceRef.strip() : null;
        // 标题也算进 checksum：只改标题同样要重建，否则检索结果里的标题永远是旧的
        String checksum = sha256Hex(normalizedTitle + "\n" + normalizedContent);
        LocalDateTime now = LocalDateTime.now();

        KbDocument existing = ref == null ? null : documentMapper.findBySourceRef(type, ref);
        if (existing != null && checksum.equals(existing.getChecksum())) {
            // 内容与标题都没变：不动切片，全文索引保持原样
            log.debug("知识库文档内容未变，跳过重建：sourceType={} sourceRef={}", type, ref);
            return existing;
        }

        String scope = StringUtils.hasText(scopeType) ? scopeType.strip().toUpperCase() : KbDocument.SCOPE_COMPANY;
        KbDocument document = existing == null ? new KbDocument() : existing;
        if (existing != null) {
            // 先删旧切片再重建：旧块一旦留在库里，就会被检索到过期内容
            chunkMapper.deleteByDocumentId(document.getId());
        }
        document.setTitle(normalizedTitle);
        document.setSourceType(type);
        document.setSourceRef(ref);
        document.setScopeType(scope);
        document.setScopeId(scopeId);
        document.setBusinessLine(businessLine);
        document.setTags(tags);
        document.setChecksum(checksum);
        document.setEnabled(existing == null || existing.getEnabled() == null ? 1 : existing.getEnabled());
        document.setUpdatedAt(now);
        if (existing == null) {
            document.setCreatedBy(operatorUserId);
            document.setCreatedAt(now);
            documentMapper.insert(document);
        } else {
            documentMapper.updateById(document);
        }

        List<KbChunkDraft> drafts = split(normalizedContent);
        for (int ordinal = 0; ordinal < drafts.size(); ordinal++) {
            KbChunkDraft draft = drafts.get(ordinal);
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(document.getId());
            chunk.setOrdinal(ordinal);
            chunk.setHeading(draft.heading());
            chunk.setContent(draft.content());
            chunk.setCharLen(draft.content().length());
            chunk.setCreatedAt(now);
            chunkMapper.insert(chunk);
        }
        log.info("知识库文档入库完成：id={} 切片 {} 块（正文 {} 字）",
                document.getId(), drafts.size(), normalizedContent.length());
        return document;
    }

    /** 某文档的切片数；文档 ID 为空时返回 0。 */
    public int chunkCount(Long documentId) {
        if (documentId == null) {
            return 0;
        }
        return chunkMapper.countByDocumentId(documentId);
    }

    /** 切块结果（落库前）：heading 可空，正文长度由调用方记到 charLen。 */
    public record KbChunkDraft(String heading, String content) {
    }

    /**
     * 切块主流程（纯函数，便于单测直接断言）。
     *
     * <p>标题行单独成段时只更新「当前小节标题」，本身不进正文——
     * 它已经由 heading 字段参与全文索引，重复进正文只会白占字数。
     */
    public static List<KbChunkDraft> split(String content) {
        List<KbChunkDraft> drafts = new ArrayList<>();
        if (!StringUtils.hasText(content)) {
            return drafts;
        }
        String normalized = normalizeNewlines(content);
        String currentHeading = null;
        for (String rawSegment : PARAGRAPH_BOUNDARY.split(normalized)) {
            String segment = rawSegment.strip();
            if (segment.isEmpty()) {
                continue;
            }
            if (isHeading(segment)) {
                currentHeading = headingText(segment);
                continue;
            }
            for (String piece : splitLongSegment(segment)) {
                drafts.add(new KbChunkDraft(currentHeading, piece));
            }
        }
        return withOverlap(drafts);
    }

    /** 段内切分：≤600 字原样返回；超长则按句末标点累加，单句本身超长就硬切。 */
    private static List<String> splitLongSegment(String segment) {
        List<String> pieces = new ArrayList<>();
        if (segment.length() <= MAX_CHUNK_CHARS) {
            pieces.add(segment);
            return pieces;
        }
        StringBuilder buffer = new StringBuilder();
        for (String sentence : SENTENCE_BOUNDARY.split(segment)) {
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > MAX_CHUNK_CHARS) {
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString());
                    buffer.setLength(0);
                }
                for (int from = 0; from < sentence.length(); from += MAX_CHUNK_CHARS) {
                    pieces.add(sentence.substring(from, Math.min(from + MAX_CHUNK_CHARS, sentence.length())));
                }
                continue;
            }
            if (buffer.length() + sentence.length() > MAX_CHUNK_CHARS) {
                pieces.add(buffer.toString());
                buffer.setLength(0);
            }
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            pieces.add(buffer.toString());
        }
        return pieces;
    }

    /**
     * 给每个块（除第一块）前置上一块的末尾 80 字。
     *
     * <p>重叠是「额外加上去的」，所以单块长度上限是 600 + 80，不是 600。
     */
    private static List<KbChunkDraft> withOverlap(List<KbChunkDraft> drafts) {
        if (drafts.size() <= 1) {
            return drafts;
        }
        List<KbChunkDraft> overlapped = new ArrayList<>(drafts.size());
        overlapped.add(drafts.get(0));
        for (int i = 1; i < drafts.size(); i++) {
            KbChunkDraft current = drafts.get(i);
            // 以「已经加过重叠的上一块」为准：保证任意相邻两块都共享 80 字，衔接不会断
            String previous = overlapped.get(i - 1).content();
            String tail = previous.length() <= OVERLAP_CHARS
                    ? previous
                    : previous.substring(previous.length() - OVERLAP_CHARS);
            overlapped.add(new KbChunkDraft(current.heading(), tail + current.content()));
        }
        return overlapped;
    }

    /** 标题行判定：只有单行段落才算，且以 # 开头或以「一、」这类中文序号开头。 */
    private static boolean isHeading(String segment) {
        if (segment.indexOf('\n') >= 0) {
            return false;
        }
        return segment.startsWith("#") || HAN_HEADING.matcher(segment).matches();
    }

    /** 去掉 markdown 井号与序号前缀，截到 300 字以内（与 kb_chunk.heading 列宽一致）。 */
    private static String headingText(String segment) {
        String text = segment.replaceFirst("^#+", "").strip();
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /** 统一换行符：前端 / 上传文件里 \r\n、\r 混着来，先归一后续切分才不会出现空段。 */
    private static String normalizeNewlines(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 内容 sha256（hex 小写）。 */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("知识库文档计算 sha256 失败", e);
        }
    }
}
