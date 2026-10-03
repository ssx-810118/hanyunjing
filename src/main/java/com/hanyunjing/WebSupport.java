package com.hanyunjing;

import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.NoSuchElementException;

@Configuration
class WebSupport implements WebMvcConfigurer {
    @Value("${app.cors-origins}") private String origins;
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins.split(",")).allowedMethods("GET","POST","PUT","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true).maxAge(3600);
    }
    static String session(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("sessionId 必须为1-64位字母、数字、下划线或短横线");
        return value;
    }
}
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(AuthService.Failure.class)
    ResponseEntity<Models.Api<Void>> auth(AuthService.Failure e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(new Models.Api<>(e.status(), e.getMessage(), null));
    }
    @ExceptionHandler(OnlineAgent.Failure.class)
    ResponseEntity<Models.Api<Void>> online(OnlineAgent.Failure e) {
        return ResponseEntity.status(e.status()).body(new Models.Api<>(e.status(), e.getMessage(), null));
    }
    // The client has disconnected: the response cannot be written, especially for SSE.
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    void disconnected(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) { }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<Models.Api<Void>> bad(Exception e) {
        String message = e instanceof IllegalArgumentException ? e.getMessage() : "请求字段缺失、格式错误或超出范围";
        return ResponseEntity.badRequest().body(new Models.Api<>(400, message, null));
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Models.Api<Void>> missing(Exception e) { return ResponseEntity.status(404).body(new Models.Api<>(404,"资源不存在或不属于当前会话",null)); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Models.Api<Void>> conflict(Exception e) { return ResponseEntity.status(409).body(new Models.Api<>(409,e.getMessage(),null)); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Models.Api<Void>> large(Exception e) { return ResponseEntity.status(413).body(new Models.Api<>(413,"图片不得超过5MB",null)); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Models.Api<Void>> other(Exception e) { return ResponseEntity.status(500).body(new Models.Api<>(500,"服务内部错误",null)); }
}
