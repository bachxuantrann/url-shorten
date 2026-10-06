package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.request.LoginRequest;
import com.auradev.url_shortener.dto.request.RefreshTokenRequest;
import com.auradev.url_shortener.dto.request.RegisterRequest;
import com.auradev.url_shortener.dto.response.LoginResponse;
import com.auradev.url_shortener.dto.response.TokenResponse;
import com.auradev.url_shortener.dto.response.UserResponse;

/**
 * Interface xử lý nghiệp vụ xác thực (Authentication).
 */
public interface AuthService {

    /**
     * Đăng ký tài khoản mới.
     *
     * @param request thông tin đăng ký
     * @return thông tin user vừa tạo
     * @throws com.auradev.url_shortener.exception.AppException nếu username/email đã tồn tại
     */
    UserResponse register(RegisterRequest request);

    /**
     * Đăng nhập — xác thực credentials và trả về JWT tokens.
     *
     * @param request thông tin đăng nhập
     * @return access token + refresh token + user info
     */
    LoginResponse login(LoginRequest request);

    /**
     * Làm mới access token bằng refresh token hợp lệ.
     *
     * @param request chứa refresh token
     * @return access token mới
     */
    TokenResponse refreshToken(RefreshTokenRequest request);

    /**
     * Đăng xuất thiết bị hiện tại.
     * Blacklist access token và xoá refresh token khỏi Redis.
     *
     * @param accessToken access token hiện tại (raw string, không có "Bearer ")
     */
    void logout(String accessToken);
}
