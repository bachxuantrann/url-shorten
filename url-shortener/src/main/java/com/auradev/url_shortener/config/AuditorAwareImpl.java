package com.auradev.url_shortener.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Cung cấp "người thực hiện" cho auditing ({@code createdBy} / {@code updatedBy}).
 *
 * <ul>
 *   <li>Request đã xác thực → username (principal do {@code JwtAuthenticationFilter} set)</li>
 *   <li>Request ẩn danh (ví dụ đăng ký tài khoản) → {@value #ANONYMOUS}</li>
 *   <li>Không có request (DataInitializer, job định kỳ, Kafka consumer) → {@value #SYSTEM}</li>
 * </ul>
 */
@Component("auditorAware")
public class AuditorAwareImpl implements AuditorAware<String> {

    public static final String SYSTEM    = "system";
    public static final String ANONYMOUS = "anonymous";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            return Optional.of(SYSTEM);
        }
        if (authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            return Optional.of(ANONYMOUS);
        }
        return Optional.of(authentication.getName());
    }
}
