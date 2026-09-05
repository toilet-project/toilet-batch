package com.example.toiletbatch.geocoding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class KakaoAddressGeocodingClient {

    private final RestClient restClient;
    private final KakaoGeocodingProperties properties;
    private final ObjectMapper objectMapper;

    public KakaoAddressGeocodingClient(
            RestClient.Builder restClientBuilder,
            KakaoGeocodingProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Optional<Coordinate> geocode(String address) {
        return request(address, false);
    }

    /** Normalization must not silently pick the first of several address candidates. */
    public Optional<Coordinate> geocodeUnique(String address) {
        return request(address, true);
    }

    private Optional<Coordinate> request(String address, boolean unique) {
        if (!StringUtils.hasText(properties.restApiKey())) {
            throw new IllegalStateException("KAKAO_REST_API_KEY 환경 변수가 설정되지 않았습니다.");
        }

        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/v2/local/search/address.json")
                .queryParam("query", address)
                .build()
                .encode()
                .toUri();

        String response = restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey())
                .retrieve()
                .body(String.class);

        return parseFirstCoordinate(response, unique);
    }

    private Optional<Coordinate> parseFirstCoordinate(String response, boolean unique) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode documents = root.path("documents");
            if (!documents.isArray() || documents.isEmpty()) {
                return Optional.empty();
            }
            if (unique && (documents.size() != 1 || root.path("meta").path("total_count").asInt(-1) != 1)) {
                return Optional.empty();
            }
            JsonNode document = documents.get(0);
            return Optional.of(new Coordinate(
                    new BigDecimal(document.path("y").asText()),
                    new BigDecimal(document.path("x").asText())
            ));
        } catch (JsonProcessingException | NumberFormatException exception) {
            throw new IllegalStateException("카카오 주소 지오코딩 응답을 해석하지 못했습니다.", exception);
        }
    }
}
