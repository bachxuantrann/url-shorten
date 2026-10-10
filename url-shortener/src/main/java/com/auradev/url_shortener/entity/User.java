package com.auradev.url_shortener.entity;

import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Entity đại diện cho tài khoản người dùng trong hệ thống.
 *
 * <p>Thiết kế:
 * <ul>
 *   <li>ID kiểu UUID (không đoán được, an toàn hơn Long khi expose ra API)</li>
 *   <li>Soft-delete thông qua {@link UserStatusEnum} thay vì xoá cứng</li>
 *   <li>Roles lưu trong bảng phụ {@code user_roles} — EAGER để không cần JOIN khi check quyền</li>
 *   <li>Audit timestamps kế thừa từ {@link BaseEntity}</li>
 * </ul>
 */
@Entity
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_username", columnList = "username", unique = true),
                @Index(name = "idx_users_email",    columnList = "email",    unique = true),
                @Index(name = "idx_users_status",   columnList = "status")
        }
)
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "uuid")
    private UUID id;

    /** Tên đăng nhập — unique, không thay đổi sau khi tạo */
    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    /** Email — unique, dùng để xác thực tài khoản */
    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** Mật khẩu đã được hash bằng BCrypt */
    @Column(name = "password", nullable = false)
    private String password;

    /** Họ và tên đầy đủ */
    @Column(name = "full_name", length = 100)
    private String fullName;

    /**
     * Trạng thái tài khoản.
     * Mặc định: {@link UserStatusEnum#ACTIVE}
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UserStatusEnum status = UserStatusEnum.ACTIVE;

    /**
     * Danh sách vai trò của người dùng.
     * Sử dụng {@code FetchType.EAGER} vì roles luôn cần khi authenticate.
     * Lưu trong bảng phụ {@code user_roles}.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            indexes = @Index(name = "idx_user_roles_user_id", columnList = "user_id")
    )
    @Column(name = "role", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<RoleEnum> roles = new HashSet<>();

    /** Xác minh email: true nếu người dùng đã xác nhận email */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Thời điểm đăng nhập gần nhất (UTC) */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
