package com.auradev.url_shortener.exception;

import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.utils.TranslatorUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorCode authError = ErrorCode.VALIDATION_FAILED;
        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .code(authError.getCode())
                .message(TranslatorUtils.toLocale(authError.getMessageKey()))
                .data(errors)
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(response, authError.getHttpStatus());
    }

    // 2. Bắt lỗi nghiệp vụ (AppException)
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        // Dịch messageKey của Enum sang đa ngôn ngữ
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey(), ex.getArgs());

        ApiResponse<Void> response = ApiResponse.error(errorCode, translatedMessage);
        return new ResponseEntity<>(response, errorCode.getHttpStatus());
    }
}
