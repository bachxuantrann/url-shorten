package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.JwtProperties;
import com.auradev.url_shortener.constant.AppConstant;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service xử lý JWT: tạo, xác thực, và trích xuất thông tin token.
 *
 * <p>Token design:
 * <ul>
 *   <li><b>Access Token</b>: ngắn hạn (15 phút), chứa {@code sub}=userId,
 *       {@code username}, {@code roles}, {@code jti} (UUID để blacklist)</li>
 *   <li><b>Refresh Token</b>: dài hạn (7 ngày), chứa {@code sub}=userId,
 *       {@code jti} (UUID để identify session)</li>
 * </ul>
 *
 * <p>Không phụ thuộc vào database — hoạt động hoàn toàn stateless.
 */
@Slf4j
@Service
public class JwtService {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    // ===========================
    //  GENERATE TOKENS
    // ===========================

    /**
     * Tạo Access Token từ thông tin User.
     * Token chứa đủ thông tin để xác thực mà không cần query DB.
     */
    public String generateAccessToken(User user, String sessionId) {
        Date now        = new Date();
        Date expiration = new Date(now.getTime() + jwtProperties.getAccessTokenExpiry());

        List<String> roles = user.getRoles().stream()
                .map(RoleEnum::name)
                .collect(Collectors.toList());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())               // jti — dùng để blacklist
                .issuer(jwtProperties.getIssuer())
                .subject(user.getId().toString())               // sub = userId (UUID)
                .claim(AppConstant.CLAIM_USERNAME, user.getUsername())
                .claim(AppConstant.CLAIM_ROLES, roles)
                .claim(AppConstant.CLAIM_SID, sessionId)        // sid = session ID
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Tạo Refresh Token — chỉ chứa userId và jti, không có roles.
     * Refresh token phải được lưu vào Redis và so sánh khi refresh.
     */
    public String generateRefreshToken(User user, String sessionId) {
        Date now        = new Date();
        Date expiration = new Date(now.getTime() + jwtProperties.getRefreshTokenExpiry());

        return Jwts.builder()
                .id(UUID.randomUUID().toString())               // jti
                .issuer(jwtProperties.getIssuer())
                .subject(user.getId().toString())               // sub = userId
                .claim(AppConstant.CLAIM_SID, sessionId)        // sid = session ID
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    // ===========================
    //  EXTRACT CLAIMS
    // ===========================

    /** Trả về toàn bộ claims của token (throw exception nếu invalid/expired). */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Trả về toàn bộ claims, bỏ qua lỗi hết hạn (dùng cho logout khi token đã expire). */
    public Claims extractAllClaimsIgnoresExpiration(String token) {
        try {
            return extractAllClaims(token);
        } catch (ExpiredJwtException e) {
            return e.getClaims();
        }
    }

    /** Trích xuất userId (subject) từ token. */
    public UUID extractUserId(String token) {
        return UUID.fromString(extractAllClaimsIgnoresExpiration(token).getSubject());
    }

    /** Trích xuất username từ access token. */
    public String extractUsername(String token) {
        return extractAllClaimsIgnoresExpiration(token).get(AppConstant.CLAIM_USERNAME, String.class);
    }

    /** Trích xuất jti (JWT ID) từ token. */
    public String extractJti(String token) {
        return extractAllClaimsIgnoresExpiration(token).getId();
    }

    /** Trích xuất sessionId (sid) từ token. */
    public String extractSessionId(String token) {
        return extractAllClaimsIgnoresExpiration(token).get(AppConstant.CLAIM_SID, String.class);
    }



    /**
     * Trích xuất danh sách roles từ access token.
     * Trả về Set<String> để convert sang Spring Security GrantedAuthority.
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(String token) {
        List<String> roles = extractAllClaimsIgnoresExpiration(token)
                .get(AppConstant.CLAIM_ROLES, List.class);
        return roles == null ? Set.of() : Set.copyOf(roles);
    }

    // ===========================
    //  VALIDATION
    // ===========================

    /**
     * Kiểm tra token có hợp lệ không.
     *
     * @return {@code true} nếu token đúng signature và chưa hết hạn
     * @throws ExpiredJwtException nếu token đã hết hạn (để caller phân biệt)
     * @throws JwtException        nếu token invalid (malformed, wrong signature…)
     */
    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            throw e; // Re-throw để filter/handler xử lý đúng error code
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT invalid: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Tính thời gian còn lại của token (dùng để set TTL blacklist trong Redis).
     *
     * @return {@link Duration} thời gian còn lại; {@link Duration#ZERO} nếu đã hết hạn
     */
    public Duration getRemainingTtl(String token) {
        try {
            Date expiration = extractAllClaims(token).getExpiration();
            long remaining  = expiration.getTime() - System.currentTimeMillis();
            return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ZERO;
        } catch (ExpiredJwtException e) {
            // Token đã hết hạn — extract từ exception để tránh re-parse
            long remaining = e.getClaims().getExpiration().getTime() - System.currentTimeMillis();
            return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ZERO;
        }
    }

    /** Thời gian sống access token (giây) — dùng để trả về trong LoginResponse. */
    public long getAccessTokenExpirySeconds() {
        return jwtProperties.getAccessTokenExpiry() / 1000;
    }
}
