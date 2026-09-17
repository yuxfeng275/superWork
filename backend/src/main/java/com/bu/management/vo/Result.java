package com.bu.management.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一响应结果
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /**
     * 状态码
     */
    private Integer code;

    /**
     * 消息
     */
    private String message;

    /**
     * 数据
     */
    private T data;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    /**
     * 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data, LocalDateTime.now());
    }

    /**
     * 成功响应（自定义消息）
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data, LocalDateTime.now());
    }

    /**
     * 成功响应（无数据）
     */
    public static Result<Void> success() {
        return new Result<>(200, "success", null, LocalDateTime.now());
    }

    /**
     * 失败响应
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null, LocalDateTime.now());
    }

    /**
     * 失败响应（默认500）
     */
    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}
