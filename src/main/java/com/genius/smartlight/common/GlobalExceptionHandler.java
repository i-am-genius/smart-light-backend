package com.genius.smartlight.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public CommonResult<String> handleServiceException(ServiceException ex, HttpServletRequest request) {
        log.warn("Service exception, msg={}, {}", ex.getMessage(), RequestLogUtils.logContext(request));
        return CommonResult.error(400, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<String> handleValidException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        log.warn("Validation exception, msg={}, {}", message, RequestLogUtils.logContext(request));
        return CommonResult.error(400, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public CommonResult<String> handleMaxUploadSizeException(MaxUploadSizeExceededException ex) {
        return CommonResult.error(413, "上传文件过大，请压缩后重试");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public CommonResult<String> handleNoResource(HttpServletRequest request, NoResourceFoundException ex) {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || uri.startsWith("/favicon") || uri.startsWith("/robots.txt")) {
            log.info("Harmless static request, {}", RequestLogUtils.logContext(request));
        } else {
            log.warn("No resource found, {}", RequestLogUtils.logContext(request));
        }
        return CommonResult.error(404, "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public CommonResult<String> handleException(HttpServletRequest request, Exception ex) {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || uri.startsWith("/favicon") || uri.startsWith("/robots.txt")) {
            log.info("Harmless request, {}", RequestLogUtils.logContext(request));
            return CommonResult.error(404, "资源不存在");
        }
        log.error("Unhandled server exception, {}", RequestLogUtils.logContext(request), ex);
        return CommonResult.error(500, "服务器内部错误，请稍后重试");
    }
}
