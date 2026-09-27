package com.auradev.url_shortener.utils;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class TranslatorUtils {
    private static MessageSource messageSource;

    public TranslatorUtils(MessageSource messageSource) {
        TranslatorUtils.messageSource = messageSource;
    }

    public static String toLocale(String msgCode, Object... args) {
        return messageSource.getMessage(msgCode, args, LocaleContextHolder.getLocale());
    }
}