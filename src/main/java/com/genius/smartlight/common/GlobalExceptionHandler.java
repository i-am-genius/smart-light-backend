package com.genius.smartlight.common;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public CommonResult<String> handleServiceException(ServiceException ex) {
        return CommonResult.error(400, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public CommonResult<String> handleValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage()
                : "参数校验失败";
        return CommonResult.error(400, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public CommonResult<String> handleMaxUploadSizeException(MaxUploadSizeExceededException ex) {
        return CommonResult.error(413, "上传文件过大，请压缩后重试");
    }

    @ExceptionHandler(Exception.class)
    public CommonResult<String> handleException(Exception ex) {
        log.error("Unhandled server exception", ex);
        return CommonResult.error(500, "服务器内部错误，请稍后重试");
    }
}
