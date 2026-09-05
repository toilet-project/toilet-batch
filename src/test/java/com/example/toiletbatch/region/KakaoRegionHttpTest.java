package com.example.toiletbatch.region;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;

class KakaoRegionHttpTest {
    @TempDir Path directory;
    Point point = new Point(new BigDecimal("36.3"), new BigDecimal("127.3"));
    String response = """
            {"documents":[{"region_type":"B","code":"3020012200","region_1depth_name":"대전광역시","region_2depth_name":"유성구"}]}
            """;
    @Test void retriesServerErrorThenCachesExactCoordinates() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/", e -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(requests.incrementAndGet() == 1 ? 503 : 200, bytes.length);
            e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try (var journal = new RegionJournal(directory.resolve("retry.jsonl"))) {
            var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 4, 200, "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("30200", provider.reverse(point).sigunguCode());
            provider.reverse(point);
            assertEquals(2, requests.get()); assertEquals(2, journal.callsToday(Clock.systemUTC()));
        } finally { server.stop(0); }
    }
    @Test void quotaAndAuthenticationAbortWithoutRetryStorm() throws Exception {
        for (int status : new int[]{401, 403, 429}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var requests = new AtomicInteger();
            server.createContext("/", e -> { requests.incrementAndGet(); e.sendResponseHeaders(status, -1); e.close(); });
            server.start();
            try (var journal = new RegionJournal(directory.resolve(status + ".jsonl"))) {
                var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 5, 200, "http://127.0.0.1:" + server.getAddress().getPort());
                assertThrows(RegionProvider.Stop.class, () -> provider.reverse(point)); assertEquals(1, requests.get());
            } finally { server.stop(0); }
        }
    }
    @Test void ambiguousForwardAddressIsNotSilentlySelected() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            byte[] bytes = "{\"meta\":{\"total_count\":2},\"documents\":[{\"x\":\"127.3\",\"y\":\"36.3\"}]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try (var journal = new RegionJournal(directory.resolve("ambiguous.jsonl"))) {
            var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 3, 200, "http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(provider.geocodeUnique("address").isEmpty());
        } finally { server.stop(0); }
    }
    @Test void structuredResponsesPreserveCountsCodesAndSeparateAddresses() throws Exception {
        try (var journal = new RegionJournal(directory.resolve("parse.jsonl"))) {
            var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 1, 200);
            AddressLookup lookup = provider.parseAddressLookup("""
                    {"meta":{"total_count":1},"documents":[{"x":"127.3","y":"36.3",
                    "address":{"b_code":"3020012200","address_name":"지번"},
                    "road_address":{"address_name":"도로명"}}]}
                    """);
            assertEquals("3020012200", lookup.legalDongCode()); assertEquals("도로명", lookup.roadAddress());
            assertEquals("지번", lookup.jibunAddress()); assertEquals(point.rounded(), lookup.point());
            assertEquals(3, provider.parseAddressLookup("{\"meta\":{\"total_count\":3},\"documents\":[]}").totalCount());
            ReverseAddress address = provider.parseReverseAddress("""
                    {"documents":[{"address":{"region_1depth_name":"세종특별자치시","region_2depth_name":"", "address_name":"지번"},"road_address":null}]}
                    """);
            assertNull(address.roadAddress()); assertNull(address.sigunguName());
            assertThrows(RegionProvider.Failure.class, () -> provider.parseAddressLookup("{\"documents\":[]}"));
            assertThrows(RegionProvider.Failure.class, () -> provider.parseReverseAddress("{\"documents\":[]}"));
            assertEquals(0, journal.callsToday(Clock.systemUTC()));
        }
    }
}
