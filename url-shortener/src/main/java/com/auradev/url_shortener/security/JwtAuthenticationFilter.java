package com.auradev.url_shortener.security;

import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.utils.JwtService;
import com.auradev.url_shortener.utils.TokenRedisService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT Authentication Filter — thực thi một lần cho mỗi request.
 *
 * <p>Luồng xử lý:
 * <ol>
 *   <li>Trích xuất Bearer token từ header {@code Authorization}</li>
 *   <li>Validate chữ ký và thời hạn (qua {@link JwtService})</li>
 *   <li>Kiểm tra blacklist trong Redis (qua {@link TokenRedisService})</li>
 *   <li>Trích xuất claims (userId, username, roles) từ JWT — <b>không query DB</b></li>
 *   <li>Tạo {@link UsernamePasswordAuthenticationToken} và set vào {@link SecurityContextHolder}</li>
 * </ol>
 *
 * <p>Nếu token không hợp lệ, filter không set Authentication — Spring Security sẽ tự xử lý 401.
 * Filter không throw exception ra ngoài để tránh bypass {@code GlobalExceptionHandler}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenRedisService tokenRedisService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String token = extractBearerToken(request);

        // Không có token — tiếp tục filter chain (Spring Security sẽ xử lý nếu cần auth)
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Đã có authentication rồi — không cần xử lý lại
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 1. Validate token (signature + expiry)
            jwtService.isTokenValid(token);

            // 2. Kiểm tra blacklist per-token (jti)
            String jti = jwtService.extractJti(token);
            if (tokenRedisService.isAccessTokenBlacklisted(jti)) {
                log.debug("Access token is blacklisted: jti={}", jti);
                filterChain.doFilter(request, response);
                return;
            }

            // 3. Kiểm tra global revoke timestamp (logoutAllDevices)
            //    Nếu token.issuedAt ≤ revokedAt → tất cả AT issued trước thời điểm logout đều bị reject
            UUID userId = jwtService.extractUserId(token);
            Long revokedAt = tokenRedisService.getGlobalRevokeTimestamp(userId);
            if (revokedAt != null) {
                long issuedAt = jwtService.extractIssuedAt(token).getTime();
                if (issuedAt <= revokedAt) {
                    log.debug("Token issued before global revoke timestamp, rejecting: userId={}", userId);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // 4. Trích xuất thông tin từ token — KHÔNG query DB
            String username  = jwtService.extractUsername(token);
            Set<String> roles = jwtService.extractRoles(token);

            // 5. Tạo authorities từ roles trong JWT
            var authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            // 6. Tạo Authentication object
            var authentication = new UsernamePasswordAuthenticationToken(
                    username,   // principal — username làm định danh
                    null,       // credentials — không cần (stateless)
                    authorities
            );
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );

            // Gắn thêm userId vào request attribute để Controller dùng nếu cần
            request.setAttribute("authenticatedUserId", userId);

            // 7. Set vào SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated user: username={}, userId={}, roles={}", username, userId, roles);

        } catch (ExpiredJwtException e) {
            log.debug("JWT expired for request {}: {}", request.getRequestURI(), e.getMessage());
            // Không set Authentication — Spring Security trả 401
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalid for request {}: {}", request.getRequestURI(), e.getMessage());
            // Không set Authentication — Spring Security trả 401
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Trích xuất Bearer token từ header {@code Authorization}.
     *
     * @return token string, hoặc {@code null} nếu không có / không đúng format
     */
    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(AppConstant.BEARER_PREFIX)) {
            return authHeader.substring(AppConstant.BEARER_PREFIX.length());
        }
        return null;
    }
}
