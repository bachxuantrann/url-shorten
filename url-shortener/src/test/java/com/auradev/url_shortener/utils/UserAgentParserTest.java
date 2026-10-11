package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.utils.UserAgentParser.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // desktop
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36|DESKTOP|Chrome|Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0|DESKTOP|Edge|Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 OPR/112.0.0.0|DESKTOP|Opera|Windows",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15|DESKTOP|Safari|macOS",
            "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0|DESKTOP|Firefox|Linux",
            "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36|DESKTOP|Chrome|ChromeOS",
            "Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.1; Trident/6.0)|DESKTOP|Internet Explorer|Windows",
            // mobile
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1|MOBILE|Safari|iOS",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.0.0 Mobile/15E148 Safari/604.1|MOBILE|Chrome|iOS",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36|MOBILE|Chrome|Android",
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36|MOBILE|Samsung Internet|Android",
            // tablet
            "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1|TABLET|Safari|iOS",
            "Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36|TABLET|Chrome|Android",
    })
    void classifiesCommonBrowsers(String userAgent, String device, String browser, String os) {
        Parsed parsed = UserAgentParser.parse(userAgent);

        assertThat(parsed.deviceType()).isEqualTo(device);
        assertThat(parsed.browser()).isEqualTo(browser);
        assertThat(parsed.os()).isEqualTo(os);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "curl/8.5.0",
            "python-requests/2.32.0",
            "WhatsApp/2.23.20.0",
    })
    void marksCrawlersAndScriptsAsBots(String userAgent) {
        Parsed parsed = UserAgentParser.parse(userAgent);

        assertThat(parsed.deviceType()).isEqualTo("BOT");
        assertThat(parsed.browser()).isEqualTo("Bot");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingUserAgentIsUnknown(String userAgent) {
        Parsed parsed = UserAgentParser.parse(userAgent);

        assertThat(parsed).isEqualTo(new Parsed("UNKNOWN", "Unknown", "Unknown"));
    }

    @Test
    void unrecognisedUserAgentFallsBackToOther() {
        Parsed parsed = UserAgentParser.parse("SomeCustomClient/1.0");

        assertThat(parsed).isEqualTo(new Parsed("DESKTOP", "Other", "Other"));
    }

    @Test
    void resultsFitTheColumnLimits() {
        Parsed parsed = UserAgentParser.parse("Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S918B) SamsungBrowser/25.0 Mobile");

        assertThat(parsed.deviceType().length()).isLessThanOrEqualTo(16);
        assertThat(parsed.browser().length()).isLessThanOrEqualTo(32);
        assertThat(parsed.os().length()).isLessThanOrEqualTo(32);
    }
}
