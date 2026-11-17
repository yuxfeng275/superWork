package com.bu.management.service;

import com.bu.management.mapper.KbChunkMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 知识库检索：只做「问句 → 命中块 → 可注入模型的片段」这一条链路。
 *
 * <p>权限口径全部压在 {@link KbChunkMapper#search} 的 SQL 里：
 * COMPANY 全员可见；OWNER 只有本人；PROJECT / BUSINESS_LINE 必须调用方显式传各自的 ID
 * 才放行。这是有意为之的保守默认——不传就只召回 COMPANY 与本人的 OWNER 文档，
 * 宁可少召回，也不把别人的项目文档捞给模型。
 *
 * <p>检索故障与空召回严格区分：前者抛 {@link KbSearchException}，后者返回空列表。
 * 吞成空召回会让模型把「检索不了」当成「知识库没有」，进而凭常识编造。
 *
 * @author BU Team
 * @since 2026-11-22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbSearchService {

    /** 默认返回条数：够模型引用的量，再多只会稀释 prompt。 */
    public static final int DEFAULT_LIMIT = 5;

    /** 条数上限：硬顶 10，防止调用方传个大数把上下文撑爆。 */
    public static final int MAX_LIMIT = 10;

    /** 片段默认预算字数。 */
    public static final int DEFAULT_SNIPPET_CHARS = 2000;

    /**
     * 问句最短字数：ngram_token_size=2，1 个字的问句切不出任何 token，
     * MySQL 侧必然 0 命中，不如在入口直接短路，省一次全表扫描。
     */
    private static final int MIN_QUERY_CHARS = 2;

    /** 片段里每块正文的展示上限，避免单块霸占整个预算。 */
    private static final int SNIPPET_CHUNK_CHARS = 400;

    private final KbChunkMapper chunkMapper;

    /**
     * 检索范围：项目与业务线分开传，ID 空间独立，不能合并成一个 scopeId。
     *
     * @param projectId      项目 ID；null 表示不召回 PROJECT 文档
     * @param businessLineId 业务线 ID；null 表示不召回 BUSINESS_LINE 文档
     */
    public record Scope(Long projectId, Long businessLineId) {

        /** 只召回到 COMPANY 与本人 OWNER 的保守范围。 */
        public static Scope none() {
            return new Scope(null, null);
        }

        /** 项目范围：调用方必须先确认用户对该项目有读权限。 */
        public static Scope project(Long projectId) {
            return new Scope(projectId, null);
        }

        /** 业务线范围：调用方必须先确认用户属于该业务线。 */
        public static Scope businessLine(Long businessLineId) {
            return new Scope(null, businessLineId);
        }
    }

    /**
     * 检索。
     *
     * @param query  用户问句
     * @param userId 调用人（放行 OWNER 范围用）
     * @param scope  范围，null 按 {@link Scope#none()} 处理
     * @param limit  条数，≤0 取默认 5，最大 10
     * @return 按相关度倒序的命中，永不为 null
     * @throws KbSearchException 数据库/索引故障：调用方必须看见「检索不了」，而不是空召回
     */
    public List<KbHit> search(String query, Long userId, Scope scope, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String q = query.strip();
        if (q.length() < MIN_QUERY_CHARS) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Scope effectiveScope = scope == null ? Scope.none() : scope;
        List<Map<String, Object>> rows;
        try {
            rows = chunkMapper.search(q, userId, effectiveScope.projectId(),
                    effectiveScope.businessLineId(), effectiveLimit);
        } catch (Exception e) {
            // 空召回与检索故障是两件事：这里必须让上游看见故障，不能伪装成「没检索到」
            log.error("知识库检索失败：query={} projectId={} businessLineId={} 原因={}",
                    q, effectiveScope.projectId(), effectiveScope.businessLineId(), e.getMessage());
            throw new KbSearchException("知识库检索暂时不可用，请稍后重试", e);
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<KbHit> hits = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row != null) {
                hits.add(toHit(row));
            }
        }
        return hits;
    }

    /**
     * 拼成可直接注入模型的片段块。
     *
     * @param hits     命中列表
     * @param maxChars 总字数预算，≤0 取默认 2000
     * @return 形如「【知识库片段】\n[1] 《文档》- 小节\n正文」的文本，无命中返回空串
     */
    public String buildContextSnippet(List<KbHit> hits, int maxChars) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        int budget = maxChars > 0 ? maxChars : DEFAULT_SNIPPET_CHARS;
        StringBuilder snippet = new StringBuilder("【知识库片段】");
        int index = 0;
        for (KbHit hit : hits) {
            if (hit == null) {
                continue;
            }
            index++;
            String head = "\n[" + index + "] 《" + nullToEmpty(hit.documentTitle()) + "》"
                    + (StringUtils.hasText(hit.heading()) ? "- " + hit.heading() : "");
            String body = shorten(nullToEmpty(hit.content()).strip(), SNIPPET_CHUNK_CHARS);
            int remain = budget - snippet.length() - head.length() - 1;
            if (remain <= 0) {
                break;
            }
            snippet.append(head).append('\n');
            snippet.append(body, 0, Math.min(remain, body.length()));
            if (remain < body.length()) {
                // 预算用尽：宁可少给一段，也不让片段超出预算
                break;
            }
        }
        return snippet.toString();
    }

    /** 行 → 命中；列名可能是 camelCase（SQL 别名）也可能是 snake_case，统一在这里收口。 */
    private KbHit toHit(Map<String, Object> row) {
        Map<String, Object> normalized = normalizeRow(row);
        return new KbHit(
                asLong(normalized.get("id")),
                asLong(normalized.get("documentId")),
                asString(normalized.get("documentTitle")),
                asString(normalized.get("heading")),
                asString(normalized.get("content")),
                asDouble(normalized.get("score")));
    }

    /** 把 snake_case 键转成 camelCase：Map 结果不经过实体的驼峰映射，列名靠这里拉齐。 */
    private static Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new HashMap<>(row.size() * 2);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.indexOf('_') >= 0) {
                StringBuilder camel = new StringBuilder(key.length());
                boolean upperNext = false;
                for (char c : key.toCharArray()) {
                    if (c == '_') {
                        upperNext = true;
                    } else {
                        camel.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                        upperNext = false;
                    }
                }
                normalized.put(camel.toString(), entry.getValue());
            } else {
                normalized.put(key, entry.getValue());
            }
        }
        return normalized;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static String shorten(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars) + "…" : text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
