package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.repository.UrlClickRepository;
import com.auradev.url_shortener.repository.UrlClickRepository.ClickRow;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.repository.UrlRepository.IdAndCode;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.utils.UserAgentParser;
import com.auradev.url_shortener.utils.UserAgentParser.Parsed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Triển khai {@link ClickIngestService}: số câu lệnh SQL cho một lô là hằng số, không phụ thuộc số event
 * (một query đổi mã sang id, một câu lệnh ghi). Làm giàu User-Agent diễn ra ở đây, không phải ở
 * producer, để request redirect không tốn CPU cho việc đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickIngestServiceImpl implements ClickIngestService {

    private final UrlRepository      urlRepository;
    private final UrlClickRepository clickRepository;

    @Override
    @Transactional
    public int ingest(List<ClickEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }

        Set<String> codes = events.stream().map(ClickEvent::shortCode).collect(Collectors.toSet());
        Map<String, Long> idByCode = new HashMap<>();
        for (IdAndCode row : urlRepository.findIdsByShortCodeIn(codes)) {
            idByCode.put(row.getShortCode(), row.getId());
        }

        List<ClickRow> rows = events.stream()
                .filter(event -> {
                    boolean known = idByCode.containsKey(event.shortCode());
                    if (!known) {
                        log.warn("Dropping click for unknown short code {} (eventId={})", event.shortCode(), event.eventId());
                    }
                    return known;
                })
                .map(event -> toRow(event, idByCode.get(event.shortCode())))
                .toList();

        return clickRepository.insertBatch(rows);
    }

    private ClickRow toRow(ClickEvent event, long urlId) {
        Parsed ua = UserAgentParser.parse(event.userAgent());
        return new ClickRow(
                UUID.fromString(event.eventId()),
                urlId,
                event.clickedAt(),
                event.visitorHash(),
                event.country(),
                ua.deviceType(),
                ua.browser(),
                ua.os());
    }
}
