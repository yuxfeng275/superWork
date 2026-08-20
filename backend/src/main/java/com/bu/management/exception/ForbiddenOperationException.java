package com.bu.management.exception;

/**
 * 业务权限拒绝异常，由全局异常处理器稳定映射为 HTTP 403。
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
