package com.auradev.url_shortener.mapper;

import com.auradev.url_shortener.dto.response.BlockedDomainResponse;
import com.auradev.url_shortener.entity.BlockedDomain;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper cho {@link BlockedDomain}.
 */
@Mapper
public interface BlockedDomainMapper {

    @Mapping(target = "blockedLinks", ignore = true)
    BlockedDomainResponse toResponse(BlockedDomain domain);
}
