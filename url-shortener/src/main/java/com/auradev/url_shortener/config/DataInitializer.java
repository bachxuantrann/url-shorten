package com.auradev.url_shortener.config;

import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import com.auradev.url_shortener.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Khởi tạo dữ liệu mặc định khi ứng dụng start.
 *
 * <p>Chạy một lần duy nhất sau khi ApplicationContext đã sẵn sàng
 * (implements {@link ApplicationRunner} — chạy sau khi Flyway migrate xong).
 *
 * <p>Logic:
 * <ul>
 *   <li>Lần đầu: tạo tài khoản admin từ {@link AdminProperties} (bind từ application.yaml).</li>
 *   <li>Các lần sau: kiểm tra username admin đã tồn tại → bỏ qua.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;

    // ===========================
    //  ENTRY POINT
    // ===========================

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        initAdminAccount();
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private void initAdminAccount() {
        if (userRepository.existsByUsername(adminProperties.getUsername())) {
            log.info("[DataInitializer] Admin account '{}' already exists — skipping.",
                    adminProperties.getUsername());
            return;
        }

        User admin = User.builder()
                .username(adminProperties.getUsername())
                .email(adminProperties.getEmail())
                .password(passwordEncoder.encode(adminProperties.getPassword()))
                .fullName(adminProperties.getFullName())
                .status(UserStatusEnum.ACTIVE)
                .roles(Set.of(RoleEnum.ROLE_ADMIN, RoleEnum.ROLE_USER))
                .emailVerified(true)
                .build();

        userRepository.save(admin);
        log.info("[DataInitializer] Admin account '{}' created successfully.",
                adminProperties.getUsername());
    }
}
