package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.enums.UserStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository cho entity {@link User}.
 * Kế thừa {@link JpaRepository} để có đầy đủ CRUD + phân trang mặc định.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // ===== Lookup =====

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    // ===== Existence checks =====

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByUsernameAndIdNot(String username, UUID id);

    boolean existsByEmailAndIdNot(String email, UUID id);

    // ===== Admin filters =====

    /** Số user ACTIVE có vai trò {@code role}, không tính {@code excludeId}: để không gỡ quản trị viên cuối cùng. */
    @Query("SELECT count(DISTINCT u) FROM User u JOIN u.roles r WHERE r = :role AND u.id <> :excludeId AND u.status = com.auradev.url_shortener.enums.UserStatusEnum.ACTIVE")
    long countActiveWithRoleExcluding(@Param("role") RoleEnum role, @Param("excludeId") UUID excludeId);

    /** Tìm user theo status */
    Page<User> findByStatus(UserStatusEnum status, Pageable pageable);

    /** Tìm user theo role */
    @Query("SELECT u FROM User u JOIN u.roles r WHERE r = :role")
    Page<User> findByRole(@Param("role") RoleEnum role, Pageable pageable);

    /** Tìm user theo status VÀ role */
    @Query("SELECT u FROM User u JOIN u.roles r WHERE u.status = :status AND r = :role")
    Page<User> findByStatusAndRole(
            @Param("status") UserStatusEnum status,
            @Param("role") RoleEnum role,
            Pageable pageable
    );

    /** Tìm kiếm theo username hoặc email (partial, case-insensitive) */
    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<User> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Tìm kiếm kết hợp keyword + status + role. {@code pattern} luôn khác null (mẫu từ {@code LikePatterns.contains},
     * {@code "%"} khi không có từ khoá): để null thì Postgres suy ra kiểu bytea và báo "lower(bytea) does not exist".
     */
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN u.roles r " +
            "WHERE (LOWER(u.username) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.email) LIKE :pattern ESCAPE '!' " +
            "   OR LOWER(u.fullName) LIKE :pattern ESCAPE '!') " +
            "AND (:status IS NULL OR u.status = :status) " +
            "AND (:role IS NULL OR r = :role)")
    Page<User> findWithFilters(
            @Param("pattern") String pattern,
            @Param("status") UserStatusEnum status,
            @Param("role") RoleEnum role,
            Pageable pageable
    );

    // ===== Updates =====

    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :time WHERE u.id = :id")
    void updateLastLoginAt(@Param("id") UUID id, @Param("time") LocalDateTime time);

    @Modifying
    @Query("UPDATE User u SET u.status = :status WHERE u.id = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") UserStatusEnum status);
}
