package com.example.toiletbatch.publicdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PublicRestroomApiClientTest {

    private HttpServer server;
    private final List<String> requestedQueries = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/public_restroom_info_v2/info_v2", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void fetchesEveryPageWithInclusiveAndExclusiveUpdateRange() {
        PublicRestroomApiClient client = new PublicRestroomApiClient(
                RestClient.builder(),
                new PublicRestroomApiProperties(baseUrl(), "test-key", 2),
                new PublicRestroomResponseParser(new ObjectMapper())
        );

        List<PublicRestroomRecord> result = client.fetchAllUpdatedBetween(
                LocalDateTime.of(2026, 8, 20, 0, 0),
                LocalDateTime.of(2026, 8, 21, 0, 0)
        );

        assertEquals(3, result.size());
        assertEquals("A-001", result.getFirst().managementNumber());
        assertEquals("테스트 화장실 3", result.getLast().name());
        assertEquals("20260820000000", result.getFirst().dataUpdatedAt());
        assertEquals(2, requestedQueries.size());
        String firstQuery = URLDecoder.decode(requestedQueries.getFirst(), StandardCharsets.UTF_8);
        assertTrue(firstQuery.contains("cond[DAT_UPDT_PNT::GTE]=20260820000000"), firstQuery);
        assertTrue(firstQuery.contains("cond[DAT_UPDT_PNT::LT]=20260821000000"), firstQuery);
        assertTrue(requestedQueries.get(1).contains("pageNo=2"));
    }

    private URI baseUrl() {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/public_restroom_info_v2");
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestedQueries.add(exchange.getRequestURI().getRawQuery());
        boolean secondPage = exchange.getRequestURI().getRawQuery().contains("pageNo=2");
        String items = secondPage
                ? "[{\"MNG_NO\":\"A-003\",\"FCLTY_NM\":\"테스트 화장실 3\",\"DAT_UPDT_PNT\":\"20260820020000\"}]"
                : "[{\"MNG_NO\":\"A-001\",\"FCLTY_NM\":\"테스트 화장실 1\",\"LAT\":\"36.3504\",\"LOT\":\"127.3845\",\"MALE_WTRCLS_CNT\":\"2\",\"FEMAIL_WTRCLS_CNT\":\"3\",\"DAT_UPDT_PNT\":\"20260820000000\"},{\"MNG_NO\":\"A-002\",\"FCLTY_NM\":\"테스트 화장실 2\",\"DAT_UPDT_PNT\":\"20260820010000\"}]";
        String body = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "},\"pageNo\":\"" + (secondPage ? 2 : 1) + "\",\"numOfRows\":\"2\",\"totalCount\":\"3\"}}}";
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
