package com.auradev.url_shortener.mapper;

import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.Url;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper cho {@link Url}.
 */
@Mapper
public interface UrlMapper {

    /**
     * Map {@link Url} sang {@link UrlResponse}.
     *
     * @param baseUrl domain công khai (không có dấu {@code /} cuối) để dựng {@code shortUrl}
     */
    @Mapping(target = "shortUrl", expression = "java(baseUrl + \"/\" + url.getShortCode())")
    UrlResponse toUrlResponse(Url url, @Context String baseUrl);

    /** Như {@link #toUrlResponse} nhưng kèm chủ sở hữu và người tạo/sửa, chỉ dành cho admin. */
    @Mapping(target = "shortUrl", expression = "java(baseUrl + \"/\" + url.getShortCode())")
    AdminUrlResponse toAdminUrlResponse(Url url, @Context String baseUrl);
}
