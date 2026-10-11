package com.auradev.url_shortener.service;

import com.auradev.url_shortener.config.ClickProperties;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tra mã quốc gia (ISO 2 ký tự) từ IP bằng file MaxMind GeoLite2-Country.
 *
 * <p>Chạy ở phía producer vì event Kafka cố ý không mang IP: tra cứu là đọc file map vào bộ nhớ (cỡ
 * micro giây), không gọi mạng, nên không làm chậm redirect. Chưa cấu hình file thì luôn trả {@code null}.
 *
 * <p>Chỉ nhận chuỗi IP thuần: tên host sẽ bị bỏ qua để {@code InetAddress} không bao giờ phải tra DNS.
 */
@Slf4j
@Service
public class GeoIpService implements DisposableBean {

    private static final Pattern IPV4 = Pattern.compile("^[0-9]{1,3}([.][0-9]{1,3}){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final DatabaseReader reader;

    public GeoIpService(ClickProperties properties) throws IOException {
        String path = properties.getGeoipDatabase();
        if (path == null || path.isBlank()) {
            this.reader = null;
            log.info("GeoIP disabled (app.click.geoip-database is empty): click country will be null");
            return;
        }
        File file = new File(path.trim());
        if (!file.isFile()) {
            throw new IllegalStateException("app.click.geoip-database points to a missing file: " + file.getAbsolutePath());
        }
        this.reader = new DatabaseReader.Builder(file).build();
        log.info("GeoIP database loaded: {} ({})", file.getName(), reader.getMetadata().getDatabaseType());
    }

    /** @return mã quốc gia viết hoa, hoặc {@code null} nếu không tra được (IP nội bộ, không có trong DB, lỗi) */
    public String countryOf(String ip) {
        if (reader == null || ip == null || !isIpLiteral(ip)) {
            return null;
        }
        try {
            Optional<CountryResponse> response = reader.tryCountry(InetAddress.getByName(ip));
            return response.map(r -> r.getCountry().getIsoCode()).filter(code -> code != null && code.length() == 2).orElse(null);
        } catch (Exception e) {
            log.debug("GeoIP lookup failed for an address: {}", e.toString());
            return null;
        }
    }

    public boolean isEnabled() {
        return reader != null;
    }

    @Override
    public void destroy() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }

    private static boolean isIpLiteral(String ip) {
        return IPV4.matcher(ip).matches() || (ip.indexOf(':') >= 0 && IPV6.matcher(ip).matches());
    }
}
