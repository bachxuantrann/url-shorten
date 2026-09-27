package com.auradev.url_shortener.exception;

import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.utils.TranslatorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Xử lý tập trung tất cả exception của ứng dụng.
 *
 * <p>Thứ tự ưu tiên (từ cụ thể → tổng quát):
 * <ol>
 *   <li>{@link MethodArgumentNotValidException} — Validation DTO (400)</li>
 *   <li>{@link AppException}                    — Lỗi nghiệp vụ tùy biến</li>
 *   <li>{@link AccessDeniedException}           — Không đủ quyền (403)</li>
 *   <li>{@link AuthenticationException}         — Chưa xác thực (401)</li>
 *   <li>{@link MissingRequestHeaderException}   — Header bắt buộc bị thiếu (400)</li>
 *   <li>{@link MethodArgumentTypeMismatchException} — Sai kiểu tham số (400)</li>
 *   <li>{@link Exception}                       — Lỗi hệ thống không xác định (500)</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ===========================
    //  1. VALIDATION (400)
    // ===========================

    /**
     * Bắt lỗi validation từ @Valid trên RequestBody.
     * Trả về map {fieldName: errorMessage} đã được dịch theo ngôn ngữ của client.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex
    ) {
        Map<String, String> errors = new LinkedHashMap<>();

        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName     = ((FieldError) error).getField();
            // DefaultMessage là message key (ví dụ: {validation.username.required})
            // Spring đã resolve thành message string qua MessageSource
            String errorMessage  = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .code(errorCode.getCode())
                .message(TranslatorUtils.toLocale(errorCode.getMessageKey()))
                .data(errors)
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(response, errorCode.getHttpStatus());
    }

    // ===========================
    //  2. APP EXCEPTION (Business Logic)
    // ===========================

    /**
     * Bắt lỗi nghiệp vụ {@link AppException} — trả về HTTP status tương ứng từ {@link ErrorCode}.
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        ErrorCode errorCode       = ex.getErrorCode();
        String translatedMessage  = TranslatorUtils.toLocale(errorCode.getMessageKey(), ex.getArgs());

        log.warn("AppException: code={}, message={}", errorCode.getCode(), translatedMessage);

        ApiResponse<Void> response = ApiResponse.error(errorCode, translatedMessage);
        return new ResponseEntity<>(response, errorCode.getHttpStatus());
    }

    // ===========================
    //  3. SPRING SECURITY (401/403)
    // ===========================

    /**
     * Bắt {@link AccessDeniedException} — trả về 403 FORBIDDEN.
     * (Trường hợp token hợp lệ nhưng không đủ quyền truy cập endpoint)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex
    ) {
        ErrorCode errorCode      = ErrorCode.FORBIDDEN;
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey());

        log.warn("Access denied: {}", ex.getMessage());
        return new ResponseEntity<>(
                ApiResponse.error(errorCode, translatedMessage),
                HttpStatus.FORBIDDEN
        );
    }

    /**
     * Bắt {@link AuthenticationException} — trả về 401 UNAUTHORIZED.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex
    ) {
        ErrorCode errorCode      = ErrorCode.UNAUTHORIZED;
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey());

        log.warn("Authentication failed: {}", ex.getMessage());
        return new ResponseEntity<>(
                ApiResponse.error(errorCode, translatedMessage),
                HttpStatus.UNAUTHORIZED
        );
    }

    // ===========================
    //  4. REQUEST ERRORS (400)
    // ===========================

    /**
     * Bắt lỗi khi header bắt buộc (ví dụ: Authorization) bị thiếu.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(
            MissingRequestHeaderException ex
    ) {
        ErrorCode errorCode      = ErrorCode.VALIDATION_FAILED;
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey());

        Map<String, String> detail = Map.of("header", ex.getHeaderName() + " header is required");
        log.debug("Missing request header: {}", ex.getHeaderName());

        ApiResponse<Void> response = ApiResponse.error(errorCode, translatedMessage);
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    /**
     * Bắt lỗi khi tham số URL sai kiểu (ví dụ: UUID không hợp lệ trong @PathVariable).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex
    ) {
        ErrorCode errorCode      = ErrorCode.VALIDATION_FAILED;
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey());

        log.debug("Type mismatch: param={}, value={}", ex.getName(), ex.getValue());
        return new ResponseEntity<>(
                ApiResponse.error(errorCode, translatedMessage),
                HttpStatus.BAD_REQUEST
        );
    }

    // ===========================
    //  5. FALLBACK (500)
    // ===========================

    /**
     * Bắt tất cả exception không được xử lý — trả về 500 INTERNAL_SERVER_ERROR.
     * Log đầy đủ stack trace để debug.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknownException(Exception ex) {
        ErrorCode errorCode      = ErrorCode.UNKNOWN_ERROR;
        String translatedMessage = TranslatorUtils.toLocale(errorCode.getMessageKey());

        // Log full stack trace — KHÔNG expose detail này ra client
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        return new ResponseEntity<>(
                ApiResponse.error(errorCode, translatedMessage),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
