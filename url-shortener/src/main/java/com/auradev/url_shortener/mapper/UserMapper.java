package com.auradev.url_shortener.mapper;

import com.auradev.url_shortener.dto.request.RegisterRequest;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.entity.User;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper cho entity {@link User}.
 *
 * <p>Cấu hình:
 * <ul>
 *   <li>{@code componentModel = "spring"} — tự động là Spring Bean (đặt trong annotation processor arg)</li>
 *   <li>Không map các field nhạy cảm (password) sang response</li>
 * </ul>
 */
@Mapper
public interface UserMapper {

    /**
     * Map {@link User} entity sang {@link UserResponse} DTO.
     * Field password KHÔNG được map (không có field đó trong UserResponse).
     */
    UserResponse toUserResponse(User user);

    /**
     * Map {@link RegisterRequest} sang {@link User} entity.
     * Password sẽ được hash riêng ở tầng Service trước khi lưu.
     * {@code id}, {@code createdAt}, {@code updatedAt}, {@code status}, {@code roles}
     * sẽ được xử lý bởi JPA/Builder, không cần set ở đây.
     */
    @Mapping(target = "id",            ignore = true)
    @Mapping(target = "password",      ignore = true)
    @Mapping(target = "avatarUrl",     ignore = true)
    @Mapping(target = "status",        ignore = true)
    @Mapping(target = "roles",         ignore = true)
    @Mapping(target = "emailVerified", ignore = true)
    @Mapping(target = "lastLoginAt",   ignore = true)
    @Mapping(target = "createdAt",     ignore = true)
    @Mapping(target = "updatedAt",     ignore = true)
    User toUser(RegisterRequest request);

    /**
     * Cập nhật các field của {@link User} từ request mà không thay thế
     * toàn bộ object (chỉ update các field non-null).
     * Dùng cho API update profile.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",            ignore = true)
    @Mapping(target = "username",      ignore = true)
    @Mapping(target = "password",      ignore = true)
    @Mapping(target = "status",        ignore = true)
    @Mapping(target = "roles",         ignore = true)
    @Mapping(target = "emailVerified", ignore = true)
    @Mapping(target = "lastLoginAt",   ignore = true)
    @Mapping(target = "createdAt",     ignore = true)
    @Mapping(target = "updatedAt",     ignore = true)
    void updateUserFromRequest(
            com.auradev.url_shortener.dto.request.UpdateProfileRequest request,
            @MappingTarget User user
    );
}
