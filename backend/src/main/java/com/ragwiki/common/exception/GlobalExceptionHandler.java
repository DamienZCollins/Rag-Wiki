package com.ragwiki.common.exception;

import com.ragwiki.common.result.Result;
import com.ragwiki.common.result.ResultCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理。所有异常都转成统一返回体，HTTP 状态码取 ResultCode 中声明的值。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException ex) {
        log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
        return build(ex.getResultCode(), ex.getMessage());
    }

    /** 请求体上的 @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("；"));
        return build(ResultCode.PARAM_INVALID, message);
    }

    /** 方法参数上的 @Validated 校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("；"));
        return build(ResultCode.PARAM_INVALID, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return build(ResultCode.PARAM_INVALID, "缺少必填参数 " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ResultCode.PARAM_INVALID, "参数 " + ex.getName() + " 类型不正确");
    }

    /** 路径匹配不到任何处理器，静态资源兜底抛出的异常 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return build(ResultCode.NOT_FOUND, "路径不存在 " + ex.getResourcePath());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        return build(ResultCode.NOT_FOUND, "路径不存在 " + ex.getRequestURL());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ResultCode.METHOD_NOT_ALLOWED, "不支持 " + ex.getMethod() + " 方法");
    }

    /**
     * 客户端在流式响应过程中断开，容器写回时抛 IOException。
     * 连接已经没了，写不回任何响应体，也不该按服务端故障记日志。
     */
    @ExceptionHandler(IOException.class)
    public void handleClientAbort(IOException ex) {
        log.debug("客户端连接已断开，响应不再写出：{}", ex.getMessage());
    }

    /** 未预期异常：只在服务端记日志，响应体不暴露堆栈信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("未预期异常", ex);
        return build(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.message());
    }

    private ResponseEntity<Result<Void>> build(ResultCode resultCode, String message) {
        return ResponseEntity.status(resultCode.httpStatus())
                .body(Result.fail(resultCode.code(), message));
    }
}
