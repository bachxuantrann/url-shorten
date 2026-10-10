package com.auradev.url_shortener.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client HTTP tối giản cho integration test (đi qua security filter, JWT, validation thật).
 * <b>Không</b> tự follow redirect để test được header {@code Location}.
 */
public class ApiClient {

    public static final String PASSWORD = "Passw0rdTest1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final int port;

    public ApiClient(int port) {
        this.port = port;
    }

    public record Resp(int status, JsonNode body, HttpHeaders headers) {
        public String code() {
            return body.get("code").asText();
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    public Resp call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept-Language", "en")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
        return new Resp(response.statusCode(), json, response.headers());
    }

    /** Đăng ký user mới (username duy nhất) và trả về access token. */
    public String registerAndLogin() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String username = "user_" + suffix;

        Resp register = call("POST", "/api/v1/auth/register", null,
                "{\"username\":\"" + username + "\",\"email\":\"" + username + "@test.local\","
                        + "\"password\":\"" + PASSWORD + "\",\"fullName\":\"Test User\"}");
        assertThat(register.status()).isEqualTo(201);

        Resp login = call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).isEqualTo(200);
        return login.body().get("data").get("accessToken").asText();
    }

    /** Tạo link và trả về short code. */
    public String createUrl(String token, String originalUrl) throws Exception {
        Resp resp = call("POST", "/api/v1/urls", token, "{\"originalUrl\":\"" + originalUrl + "\"}");
        assertThat(resp.status()).isEqualTo(201);
        return resp.body().get("data").get("shortCode").asText();
    }
}
