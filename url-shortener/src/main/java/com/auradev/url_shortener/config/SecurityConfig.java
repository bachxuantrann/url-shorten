package com.auradev.url_shortener.config;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.dto.response.ApiResponse;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.security.JwtAuthenticationFilter;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.TranslatorUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cấu hình Spring Security.
 *
 * <p>Chiến lược:
 * <ul>
 *   <li>Stateless — không dùng Session, không dùng UserDetailsService</li>
 *   <li>JWT filter xác thực token trước mọi request</li>
 *   <li>BCrypt độ khó 12 (production-safe)</li>
 *   <li>401 / 403 trả về JSON chuẩn {@code ApiResponse}</li>
 *   <li>{@code @PreAuthorize} / {@code @PostAuthorize} được enable</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper            objectMapper;
    private final TranslatorUtils         translator;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ===== Tắt CSRF (stateless API) =====
            .csrf(AbstractHttpConfigurer::disable)

            // ===== Không dùng session =====
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ===== Authorization rules =====
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(AppConstant.PUBLIC_ENDPOINTS).permitAll()
                    // Redirect công khai: GET /{code}
                    .requestMatchers(redirectRequestMatcher()).permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            )


            .exceptionHandling(ex -> ex
                    // ===== 401 — Chưa xác thực =====
                    .authenticationEntryPoint((request, response, authException) -> {
                        ErrorCode errorCode = ErrorCode.UNAUTHORIZED;
                        writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, errorCode);
                    })
                    // ===== 403 — Không có quyền =====
                    .accessDeniedHandler((request, response, accessDeniedException) -> {
                        ErrorCode errorCode = ErrorCode.FORBIDDEN;
                        writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, errorCode);
                    })
            )

            // ===== Đặt JWT filter trước filter xác thực mặc định =====
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Khớp {@code GET/HEAD /{code}}: đường dẫn một đoạn, chỉ gồm ký tự hợp lệ của short code
     * và không thuộc danh sách mã dành riêng ({@code /api}, {@code /actuator}...).
     * Các đường dẫn còn lại vẫn đi theo luật {@code anyRequest().authenticated()}.
     */
    private RequestMatcher redirectRequestMatcher() {
        return request -> {
            String method = request.getMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                return false;
            }
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return ShortCodes.isRedirectPath(path);
        };
    }

    /**
     * BCrypt password encoder với strength=12.
     * Mức 12 cân bằng tốt giữa bảo mật và hiệu năng cho production.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Ghi JSON error response chuẩn khi 401/403.
     * Đảm bảo response nhất quán với {@code GlobalExceptionHandler}.
     */
    private void writeErrorResponse(
            HttpServletResponse response,
            int statusCode,
            ErrorCode errorCode
    ) {
        try {
            response.setStatus(statusCode);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            String message;
            try {
                message = translator.toLocale(errorCode.getMessageKey());
            } catch (Exception e) {
                message = errorCode.getMessageKey();
            }

            ApiResponse<Void> apiResponse = ApiResponse.error(errorCode, message);

            response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
            response.getWriter().flush();
        } catch (Exception ex) {
            // fallback — không để exception ăn mất lỗi gốc
            log.error("Cannot write error response to output stream", ex);
        }
    }
}
