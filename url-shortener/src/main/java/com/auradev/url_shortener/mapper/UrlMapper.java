package com.auradev.url_shortener.mapper;

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
}
