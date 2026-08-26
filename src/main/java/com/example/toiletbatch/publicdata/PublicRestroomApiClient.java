package com.example.toiletbatch.publicdata;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class PublicRestroomApiClient {

    private static final DateTimeFormatter UPDATED_AT_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RestClient restClient;
    private final PublicRestroomApiProperties properties;
    private final PublicRestroomResponseParser responseParser;

    public PublicRestroomApiClient(
            RestClient.Builder restClientBuilder,
            PublicRestroomApiProperties properties,
            PublicRestroomResponseParser responseParser
    ) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.responseParser = responseParser;
    }

    public List<PublicRestroomRecord> fetchAllUpdatedBetween(LocalDateTime updatedFromInclusive, LocalDateTime updatedBeforeExclusive) {
        validatePeriod(updatedFromInclusive, updatedBeforeExclusive);

        List<PublicRestroomRecord> records = new ArrayList<>();
        int pageNumber = 1;
        while (true) {
            PublicRestroomPage page = fetchPage(updatedFromInclusive, updatedBeforeExclusive, pageNumber);
            records.addAll(page.records());
            if (page.records().isEmpty() || records.size() >= page.totalCount()) {
                return List.copyOf(records);
            }
            pageNumber++;
        }
    }

    public PublicRestroomPage fetchPage(
            LocalDateTime updatedFromInclusive,
            LocalDateTime updatedBeforeExclusive,
            int pageNumber
    ) {
        validatePeriod(updatedFromInclusive, updatedBeforeExclusive);
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber는 1 이상이어야 합니다.");
        }
        if (!StringUtils.hasText(properties.serviceKey())) {
            throw new IllegalStateException("PUBLIC_DATA_API_KEY 환경 변수가 필요합니다.");
        }

        URI requestUri = UriComponentsBuilder.fromUri(properties.baseUrl())
                .path("/info_v2")
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("pageNo", pageNumber)
                .queryParam("numOfRows", properties.pageSize())
                .queryParam("returnType", "json")
                .queryParam("cond[DAT_UPDT_PNT::GTE]", UPDATED_AT_FORMATTER.format(updatedFromInclusive))
                .queryParam("cond[DAT_UPDT_PNT::LT]", UPDATED_AT_FORMATTER.format(updatedBeforeExclusive))
                .build()
                .encode()
                .toUri();

        String responseBody = restClient.get()
                .uri(requestUri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        if (responseBody == null || responseBody.isBlank()) {
            throw new PublicDataApiException("공공데이터 API가 빈 응답을 반환했습니다.");
        }
        return responseParser.parse(responseBody);
    }

    private void validatePeriod(LocalDateTime updatedFromInclusive, LocalDateTime updatedBeforeExclusive) {
        if (updatedFromInclusive == null || updatedBeforeExclusive == null) {
            throw new IllegalArgumentException("데이터 갱신시점의 시작과 끝은 필수입니다.");
        }
        if (!updatedFromInclusive.isBefore(updatedBeforeExclusive)) {
            throw new IllegalArgumentException("시작 시점은 종료 시점보다 앞서야 합니다.");
        }
    }
}
