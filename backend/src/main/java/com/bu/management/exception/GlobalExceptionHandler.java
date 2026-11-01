package com.bu.management.exception;

import com.bu.management.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * 约定：业务异常（IllegalArgument/IllegalState/Forbidden）的 message 是给用户看的；
 * 数据库/框架异常不能把 SQL、堆栈和内部字段名直接返回前端（以前会整段 MyBatis 报错显示在页面上）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("请求资源不存在: {}", e.getResourcePath());
        return Result.error(404, "资源不存在");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleResourceNotFoundException(ResourceNotFoundException e) {
        log.error("资源不存在: ", e);
        return Result.error(404, e.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleForbiddenOperationException(ForbiddenOperationException e) {
        log.warn("业务权限拒绝: {}", e.getMessage());
        return Result.error(403, e.getMessage());
    }

    /**
     * 数据库约束/长度异常：转成可操作提示，不返回 SQL 细节。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        String detail = String.valueOf(e.getMessage());
        log.warn("数据写入违反约束: {}", detail.lines().findFirst().orElse(detail));
        String message = "保存失败：数据格式不正确，请检查后重试";
        if (detail.contains("Data too long")) {
            message = "保存失败：内容超出字段长度限制，请精简后再试";
        } else if (detail.contains("Duplicate entry") || detail.contains("duplicate")) {
            message = "保存失败：记录已存在，请刷新后重试";
        } else if (detail.contains("cannot be null") || detail.contains("Column")) {
            message = "保存失败：缺少必填信息，请补充后重试";
        }
        return Result.error(400, message);
    }

    /**
     * 请求体不是合法 JSON / 字段类型不匹配。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求数据格式错误，请刷新页面后重试");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}={}", e.getName(), e.getValue());
        return Result.error(400, "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(400, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 处理运行时异常：业务文案原样返回；SQL/堆栈类文案统一兜底。
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        String message = e.getMessage();
        // 业务代码里有直接用 RuntimeException 抛业务文案（如「业务线不存在」），不能按类型一刀切；
        // 但 NPE、越界、日期解析等 JDK 异常属于技术错误，必须兜底。
        boolean technicalType = e.getClass().getName().startsWith("java.")
                && e.getClass() != RuntimeException.class
                && !(e instanceof IllegalArgumentException)
                && !(e instanceof IllegalStateException);
        if (technicalType || isTechnicalMessage(message)) {
            log.error("运行时异常: ", e);
            return Result.error(400, "操作失败，请稍后重试；若持续失败请联系管理员");
        }
        log.warn("业务异常: {}", message);
        return Result.error(400, StringUtils.hasText(message) ? message : "操作失败，请稍后重试");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("参数绑定失败: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常: ", e);
        return Result.error(500, "系统异常，请联系管理员");
    }

    /** 明显是框架/数据库技术细节的文案，不能直接给用户看。 */
    private boolean isTechnicalMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return true;
        }
        return message.contains("### Error")
                || message.contains("### Cause")
                || message.contains("### SQL")
                || message.contains("SQL:")
                || message.contains("Caused by")
                || message.contains("Data truncation")
                || message.contains("For input string")
                || message.contains("Unknown column")
                || message.contains("doesn't exist")
                || message.contains("org.springframework")
                || message.contains("java.lang.")
                || message.contains("\n\tat ");
    }
}
