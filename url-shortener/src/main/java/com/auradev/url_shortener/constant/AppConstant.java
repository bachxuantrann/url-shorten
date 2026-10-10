package com.auradev.url_shortener.constant;

/**
 * Hằng số toàn cục cho ứng dụng.
 * Sử dụng interface để các hằng số có thể được tham chiếu
 * mà không cần khởi tạo đối tượng.
 */
public interface AppConstant {

    // ===== API =====
    String API_PREFIX         = "/api/v1";
    String API_AUTH           = API_PREFIX + "/auth";
    String API_USERS          = API_PREFIX + "/users";
    String API_ADMIN          = API_PREFIX + "/admin";
    String API_URLS           = API_PREFIX + "/urls";

    // ===== JWT =====
    String BEARER_PREFIX      = "Bearer ";
    String CLAIM_USERNAME     = "username";
    String CLAIM_ROLES        = "roles";
    String CLAIM_JTI          = "jti";
    String CLAIM_SID          = "sid";

    // ===== REDIS KEY PREFIX =====
    /** Prefix lưu refresh token: RT:{userId}:{sessionId} → refreshTokenString */
    String REDIS_REFRESH_TOKEN_PREFIX   = "RT:";

    /** Prefix blacklist access token: BL:{jti} → "1" */
    String REDIS_ACCESS_BLACKLIST_PREFIX = "BL:";

    /** Prefix cache link cho redirect: URL:{shortCode} → "STATUS|expiresAt|originalUrl" hoặc "MISSING" */
    String REDIS_URL_CACHE_PREFIX        = "URL:";

    /** Prefix cache user info: USER:{userId} → UserResponse JSON */
    String REDIS_USER_CACHE_PREFIX       = "USER:";



    // ===== SECURITY =====
    String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    // ===== PAGINATION =====
    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE     = 100;
}
