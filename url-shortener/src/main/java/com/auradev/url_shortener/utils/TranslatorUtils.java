package com.auradev.url_shortener.utils;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Utility bean để resolve i18n message theo locale của request hiện tại.
 * Inject như một Spring bean thông thường — không dùng static field.
 */
@Component
@RequiredArgsConstructor
public class TranslatorUtils {

    private final MessageSource messageSource;

    /**
     * Dịch message key thành chuỗi theo locale của request hiện tại.
     *
     * @param msgCode message key trong file i18n (vd: "api.error.not_found")
     * @param args    tham số bổ sung cho message (optional)
     */
    public String toLocale(String msgCode, Object... args) {
        return messageSource.getMessage(msgCode, args, LocaleContextHolder.getLocale());
    }
}