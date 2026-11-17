package com.bu.management.service;

/**
 * 知识库检索失败：数据库异常不再被吞成「无命中」。
 *
 * <p>空召回与检索故障是两件事：前者说明语料里确实没有，模型可以继续回答或反问；
 * 后者说明这次回答没有知识库支撑，必须让调用方看见，而不是让模型以为「知识库没有」。
 * 错误码固定 {@link #CODE}，前端与工具层按它区分「没检索到」和「检索不了」。
 *
 * @author BU Team
 * @since 2026-11-17
 */
public class KbSearchException extends RuntimeException {

    /** 检索故障的稳定错误码（HTTP 503 / 工具层 errorCode 共用）。 */
    public static final String CODE = "KB_SEARCH_FAILED";

    public KbSearchException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 稳定错误码：调用方不要从 message 里解析。 */
    public String code() {
        return CODE;
    }
}
