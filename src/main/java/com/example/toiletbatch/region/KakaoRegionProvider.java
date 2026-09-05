package com.example.toiletbatch.region;

import com.example.toiletbatch.geocoding.KakaoAddressGeocodingClient;
import com.example.toiletbatch.geocoding.KakaoGeocodingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import static com.example.toiletbatch.region.RegionModel.*;

public final class KakaoRegionProvider implements RegionProvider {
    private final RestClient http;
    private final KakaoAddressGeocodingClient addresses;
    private final ObjectMapper json = new ObjectMapper();
    private final RegionJournal journal;
    private final Clock clock;
    private final int budget;
    private final long delayMillis;
    private long lastCallNanos;

    public KakaoRegionProvider(String key, RegionJournal journal, Clock clock, int budget, long delayMillis) {
        this(key, journal, clock, budget, delayMillis, "https://dapi.kakao.com");
    }
    KakaoRegionProvider(String key, RegionJournal journal, Clock clock, int budget, long delayMillis, String baseUrl) {
        if (key == null || key.isBlank()) throw new Stop("KAKAO_REST_API_KEY_MISSING");
        if (budget < 1 || delayMillis < 200) throw new IllegalArgumentException("budget >= 1 and delay >= 200ms required");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        var builder = RestClient.builder().requestFactory(factory);
        http = builder.clone().baseUrl(baseUrl).defaultHeader("Authorization", "KakaoAK " + key).build();
        addresses = new KakaoAddressGeocodingClient(builder, new KakaoGeocodingProperties(baseUrl, key), json);
        this.journal = journal; this.clock = clock; this.budget = budget; this.delayMillis = delayMillis;
    }
    @Override public Optional<Point> geocodeUnique(String address) {
        return request(() -> addresses.geocodeUnique(address).map(c -> new Point(c.latitude(), c.longitude())));
    }
    @Override public Region reverse(Point point) {
        Region cached = journal.cached(point, clock.millis());
        if (cached != null) return cached;
        String response = request(() -> http.get().uri(b -> b.path("/v2/local/geo/coord2regioncode.json")
                .queryParam("x", point.longitude().toPlainString()).queryParam("y", point.latitude().toPlainString())
                .queryParam("input_coord", "WGS84").build()).retrieve().body(String.class));
        Region region = parse(response);
        journal.cache(point, region, clock.millis());
        return region;
    }
    @Override public AddressLookup searchAddress(String address) {
        String response = request(() -> http.get().uri(b -> b.path("/v2/local/search/address.json")
                .queryParam("query", "{address}").build(address)).retrieve().body(String.class));
        return parseAddressLookup(response);
    }
    public AddressLookup parseAddressLookup(String response) {
        try {
            JsonNode root = json.readTree(response);
            JsonNode count = root.path("meta").path("total_count"), docs = root.path("documents");
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0 || !docs.isArray())
                throw new Failure("MALFORMED_ADDRESS_RESPONSE");
            int total = count.intValue();
            if (total != 1) return new AddressLookup(total, null, null, null, null);
            if (docs.size() != 1) throw new Failure("MALFORMED_ADDRESS_RESPONSE");
            JsonNode doc = docs.get(0), parcel = doc.path("address");
            Point point = new Point(new java.math.BigDecimal(doc.path("y").asText()), new java.math.BigDecimal(doc.path("x").asText()));
            if (!point.valid()) throw new Failure("INVALID_ADDRESS_COORDINATE");
            return new AddressLookup(total, point.rounded(), parcel.path("b_code").asText(),
                    nullable(doc.path("road_address"), "address_name"), nullable(parcel, "address_name"));
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("MALFORMED_ADDRESS_RESPONSE"); }
    }
    @Override public ReverseAddress reverseAddress(Point point) {
        String response = request(() -> http.get().uri(b -> b.path("/v2/local/geo/coord2address.json")
                .queryParam("x", point.longitude().toPlainString()).queryParam("y", point.latitude().toPlainString())
                .queryParam("input_coord", "WGS84").build()).retrieve().body(String.class));
        return parseReverseAddress(response);
    }
    public ReverseAddress parseReverseAddress(String response) {
        try {
            JsonNode docs = json.readTree(response).path("documents");
            if (!docs.isArray() || docs.size() != 1) throw new Failure("NO_UNIQUE_REVERSE_ADDRESS");
            JsonNode doc = docs.get(0), parcel = doc.path("address"), road = doc.path("road_address");
            String sido = nullable(parcel, "region_1depth_name"), sigungu = nullable(parcel, "region_2depth_name");
            if (sido == null) throw new Failure("MISSING_REVERSE_ADDRESS_REGION");
            // Preserve both structured regions so a disagreement is review evidence, not a network retry.
            return new ReverseAddress(sido, sigungu, nullable(road, "address_name"), nullable(parcel, "address_name"),
                    nullable(road, "region_1depth_name"), nullable(road, "region_2depth_name"));
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("MALFORMED_REVERSE_ADDRESS_RESPONSE"); }
    }
    private static String nullable(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.isEmpty() ? null : value;
    }
    public Region parse(String response) {
        try {
            JsonNode documents = json.readTree(response).path("documents");
            JsonNode legal = null;
            String administrative = null;
            for (JsonNode doc : documents) {
                if ("B".equals(doc.path("region_type").asText())) {
                    if (legal != null) throw new Failure("AMBIGUOUS_LEGAL_REGION");
                    legal = doc;
                }
                if ("H".equals(doc.path("region_type").asText())) administrative = doc.path("code").asText();
            }
            if (legal == null) throw new Failure("NO_LEGAL_REGION");
            String code = legal.path("code").asText();
            String sido = legal.path("region_1depth_name").asText().strip();
            String sigungu = legal.path("region_2depth_name").asText().strip().replaceAll("\\s+", " ");
            if (!code.matches("[1-9][0-9]{9}") || sido.isEmpty()) throw new Failure("INVALID_LEGAL_CODE");
            if (administrative != null && (!administrative.matches("[1-9][0-9]{9}") || !administrative.substring(0, 5).equals(code.substring(0, 5))))
                throw new Failure("LEGAL_ADMIN_CODE_CONFLICT");
            if (sigungu.isEmpty() && !(code.startsWith("36") && sido.equals("세종특별자치시"))) throw new Failure("MISSING_SIGUNGU");
            String city = null, district = null;
            // This split is of the provider's structured jurisdiction, NOT a toilet address.
            var match = java.util.regex.Pattern.compile("^([^ ]+시) ([^ ]+구)$").matcher(sigungu);
            if (match.matches()) { city = match.group(1); district = match.group(2); }
            else if (sigungu.contains(" ")) throw new Failure("UNSUPPORTED_REGION_HIERARCHY");
            return new Region(sido, code.substring(0, 2), sigungu.isEmpty() ? null : sigungu, code.substring(0, 5),
                    city, district, code, administrative);
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("MALFORMED_REGION_RESPONSE"); }
    }
    private <T> T request(Supplier<T> call) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long elapsed = (System.nanoTime() - lastCallNanos) / 1_000_000;
            sleep(Math.max(0, delayMillis - elapsed));
            journal.reserveCall(clock, budget);
            lastCallNanos = System.nanoTime();
            try { return call.get(); }
            catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401 || status == 403) throw new Stop("KAKAO_AUTH_OR_PERMISSION_FAILURE");
                if (status == 429) {
                    // Stop instead of burning an app-wide daily quota. Resume after operator quota check.
                    throw new Stop("KAKAO_RATE_LIMITED_CHECK_QUOTA_BEFORE_RESUME");
                }
                if (status < 500 || attempt == 2) throw new Failure("KAKAO_HTTP_" + status);
            } catch (ResourceAccessException e) {
                if (attempt == 2) throw new Failure("KAKAO_NETWORK_FAILURE");
            } catch (IllegalStateException e) { throw new Failure("MALFORMED_ADDRESS_RESPONSE"); }
            sleep((1L << attempt) * 1000 + java.util.concurrent.ThreadLocalRandom.current().nextLong(250));
        }
        throw new Failure("KAKAO_RETRY_EXHAUSTED");
    }
    private static void sleep(long ms) {
        try { if (ms > 0) Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Stop("INTERRUPTED"); }
    }
}
