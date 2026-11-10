package com.bu.management.service;

/**
 * 一条知识库命中：服务层对外只暴露这个不可变视图，不让 Map / 实体泄漏到能力层。
 *
 * @param id            切片 ID
 * @param documentId    所属文档 ID
 * @param documentTitle 文档标题（拼片段时要展示给模型）
 * @param heading       小节标题，可空
 * @param content       切片正文
 * @param score         ngram 相关度（MySQL 原始值，仅用于排序，不承诺量纲）
 * @author BU Team
 * @since 2026-11-22
 */
public record KbHit(
        Long id,
        Long documentId,
        String documentTitle,
        String heading,
        String content,
        double score) {
}
