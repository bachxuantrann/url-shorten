package com.auradev.url_shortener.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Enum quản lý toàn bộ mã lỗi của ứng dụng.
 *
 * <p>Cấu trúc mã lỗi:
 * <ul>
 *   <li>ERR_001–099  : Validation</li>
 *   <li>ERR_100–199  : User</li>
 *   <li>ERR_200–299  : Authentication / Token</li>
 *   <li>ERR_300–399  : Authorization</li>
 *   <li>ERR_400–499  : URL</li>
 *   <li>ERR_999      : Lỗi hệ thống</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    // ===== VALIDATION (001-099) =====
    VALIDATION_FAILED("ERR_001", "api.error.validation_failed", HttpStatus.BAD_REQUEST),

    // ===== USER (100-199) =====
    USER_NOT_FOUND("ERR_100", "api.error.user_not_found", HttpStatus.NOT_FOUND),
    USERNAME_EXISTS("ERR_101", "api.error.username_exists", HttpStatus.CONFLICT),
    EMAIL_EXISTS("ERR_102", "api.error.email_exists", HttpStatus.CONFLICT),
    ACCOUNT_INACTIVE("ERR_103", "api.error.account_inactive", HttpStatus.FORBIDDEN),
    ACCOUNT_BANNED("ERR_104", "api.error.account_banned", HttpStatus.FORBIDDEN),
    WRONG_OLD_PASSWORD("ERR_105", "api.error.wrong_old_password", HttpStatus.BAD_REQUEST),
    PASSWORD_NOT_MATCH("ERR_106", "api.error.password_not_match", HttpStatus.BAD_REQUEST),

    // ===== AUTHENTICATION (200-299) =====
    INVALID_CREDENTIALS("ERR_200", "api.error.invalid_credentials", HttpStatus.UNAUTHORIZED),
    INVALID_TOKEN("ERR_201", "api.error.invalid_token", HttpStatus.UNAUTHORIZED),
    TOKEN_EXPIRED("ERR_202", "api.error.token_expired", HttpStatus.UNAUTHORIZED),
    TOKEN_REVOKED("ERR_203", "api.error.token_revoked", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_NOT_FOUND("ERR_204", "api.error.refresh_token_not_found", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_MISMATCH("ERR_205", "api.error.refresh_token_mismatch", HttpStatus.UNAUTHORIZED),

    // ===== AUTHORIZATION (300-399) =====
    UNAUTHORIZED("ERR_300", "api.error.unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("ERR_301", "api.error.forbidden", HttpStatus.FORBIDDEN),

    // ===== URL (400-499) =====
    URL_NOT_FOUND("ERR_400", "api.error.url_not_found", HttpStatus.NOT_FOUND),
    URL_BLOCKED("ERR_401", "api.error.url_blocked", HttpStatus.FORBIDDEN),
    URL_INVALID_STATE("ERR_402", "api.error.url_invalid_state", HttpStatus.CONFLICT),
    URL_EXPIRED("ERR_403", "api.error.url_expired", HttpStatus.BAD_REQUEST),
    INVALID_URL("ERR_404", "api.error.invalid_url", HttpStatus.BAD_REQUEST),
    URL_GONE("ERR_405", "api.error.url_gone", HttpStatus.GONE),
    URL_ACCESS_BLOCKED("ERR_406", "api.error.url_access_blocked", HttpStatus.FORBIDDEN),

    // ===== SYSTEM (999) =====
    UNKNOWN_ERROR("ERR_999", "api.error.unknown", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String messageKey;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String messageKey, HttpStatus httpStatus) {
        this.code       = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
    }
}
