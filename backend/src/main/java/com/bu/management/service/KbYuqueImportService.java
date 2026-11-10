package com.bu.management.service;

import com.bu.management.entity.KbDocument;
import com.bu.management.integration.YuqueMcpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 语雀导入：把一个语雀仓库的文档批量切块入库。
 *
 * <p>口径：
 * <ul>
 *   <li>来源标识写成 {@code repoId/slug}，同一篇文档重复导入按内容校验和幂等；</li>
 *   <li>单篇失败不中止整批：记录失败标题与原因，全部结束后一并返回；</li>
 *   <li>语雀未启用/未配置时直接返回可读原因，不抛异常（管理页能直接展示）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbYuqueImportService {

    /** 导入结果。 */
    public record ImportResult(int imported, int skipped, List<String> failures, String message) {
    }

    private final YuqueMcpClient yuqueMcpClient;
    private final KbIngestService kbIngestService;

    public ImportResult importRepo(String repoId, String scopeType, Long scopeId, String businessLine,
                                   Long operatorUserId) {
        if (!StringUtils.hasText(repoId)) {
            return new ImportResult(0, 0, List.of(), "缺少语雀仓库 ID");
        }
        if (!yuqueMcpClient.enabled() || !yuqueMcpClient.configured()) {
            return new ImportResult(0, 0, List.of(), "语雀连接器未启用或未配置");
        }
        List<JsonNode> docs;
        try {
            docs = extractDocs(yuqueMcpClient.listDocs(repoId));
        } catch (Exception e) {
            log.warn("语雀文档列表拉取失败: repoId={} err={}", repoId, e.getMessage());
            return new ImportResult(0, 0, List.of(), "拉取语雀文档列表失败：" + e.getMessage());
        }
        if (docs.isEmpty()) {
            return new ImportResult(0, 0, List.of(), "语雀仓库里没有可导入的文档（或返回结构无法识别）");
        }
        int imported = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (JsonNode doc : docs) {
            String title = text(doc, "title");
            String slug = text(doc, "slug");
            String id = text(doc, "id");
            String key = StringUtils.hasText(slug) ? slug : id;
            if (!StringUtils.hasText(key)) {
                skipped++;
                continue;
            }
            try {
                String body = yuqueMcpClient.getDocBody(repoId, key, "markdown");
                if (!StringUtils.hasText(body)) {
                    skipped++;
                    continue;
                }
                KbDocument saved = kbIngestService.ingest(
                        StringUtils.hasText(title) ? title : key, body, "YUQUE", repoId + "/" + key,
                        scopeType, scopeId, businessLine, null, operatorUserId);
                if (saved != null) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                String name = StringUtils.hasText(title) ? title : key;
                failures.add(name + "：" + e.getMessage());
            }
        }
        log.info("语雀导入完成: repoId={} 导入={} 跳过={} 失败={}", repoId, imported, skipped, failures.size());
        return new ImportResult(imported, skipped, failures,
                "导入完成：成功 " + imported + " 篇，跳过 " + skipped + " 篇，失败 " + failures.size() + " 篇");
    }

    /**
     * 从语雀返回里兼容地找出文档数组：优先 docs / data / items，否则递归找第一个含 title+slug|id 的对象数组。
     */
    private List<JsonNode> extractDocs(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode array = null;
        if (node != null) {
            for (String field : new String[] {"docs", "data", "items", "books"}) {
                if (node.path(field).isArray()) {
                    array = node.path(field);
                    break;
                }
            }
            if (array == null) {
                array = firstDocArray(node);
            }
        }
        if (array != null) {
            array.forEach(item -> {
                if (item.isObject() && (item.has("title") || item.has("slug"))) {
                    out.add(item);
                }
            });
        }
        return out;
    }

    private JsonNode firstDocArray(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isObject() && (item.has("slug") || item.has("id")) && item.has("title")) {
                    return node;
                }
            }
            return null;
        }
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                JsonNode found = firstDocArray(it.next().getValue());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }
}
