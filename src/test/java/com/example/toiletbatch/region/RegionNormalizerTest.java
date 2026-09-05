package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionNormalizerTest {
    private final RegionProvider provider = mock(RegionProvider.class);
    private final Point point = new Point(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"));
    private final Region daejeon = new Region("대전광역시", "30", "유성구", "30200", null, null, "3020012200", "3020053000");
    private final RegionNormalizer normalizer = new RegionNormalizer(provider, Clock.systemUTC());

    @Test void normalRoadAndJibunAndCoordinates() {
        when(provider.reverse(point)).thenReturn(daejeon);
        Source source = source("대전광역시 유성구 대학로 99", "대전 유성구 궁동 1", point);
        Result r = normalizer.normalize(source);
        assertEquals(Status.VERIFIED, r.status()); assertEquals(Check.MATCH, r.roadCheck()); assertEquals(Check.MATCH, r.jibunCheck());
        assertEquals(source, r.source()); assertEquals(point, r.evaluated());
        verify(provider, never()).geocodeUnique(any());
    }
    static Stream<Arguments> addresses() {
        return Stream.of(Arguments.of("대전 유성구 대학로 99", null), Arguments.of(null, "대전광역시 유성구 궁동 1"),
                Arguments.of("대전광역시 유성구 대학로 99", "궁동 1"));
    }
    @ParameterizedTest @MethodSource("addresses") void oneUsableAddressIsSufficient(String road, String jibun) {
        when(provider.reverse(point)).thenReturn(daejeon);
        assertEquals(Status.VERIFIED, normalizer.normalize(source(road, jibun, point)).status());
    }
    @Test void coordinatesWithoutAddressesRemainReviewable() {
        when(provider.reverse(point)).thenReturn(daejeon);
        Result r = normalizer.normalize(source(null, null, point));
        assertEquals(Status.ADDRESS_UNVERIFIED, r.status()); assertNotNull(r.region());
    }
    @Test void conflictingRoadOrJibunIsNeverAutomaticallyVerified() {
        when(provider.reverse(point)).thenReturn(daejeon);
        when(provider.reverseAddress(point)).thenReturn(new ReverseAddress("대전", "유성구", null, "대전 유성구 궁동 1"));
        when(provider.searchAddress(anyString())).thenReturn(new AddressLookup(0, null, null, null, null));
        Result r = normalizer.normalize(source("부산광역시 해운대구 해운대로 1", "대전 유성구 궁동 1", point));
        assertEquals(Status.MISMATCH, r.status()); assertEquals(Check.MISMATCH, r.roadCheck());
        assertEquals(Check.MATCH, r.jibunCheck()); assertEquals(point, r.source().point());
    }
    @Test void missingCoordinatesUseRoadFirst() {
        when(provider.geocodeUnique("대전 유성구 대학로 99")).thenReturn(Optional.of(point));
        when(provider.reverse(point)).thenReturn(daejeon);
        Result r = normalizer.normalize(source("대전 유성구 대학로 99", "대전 유성구 궁동 1", null));
        assertEquals("ROAD", r.fallback()); assertEquals(Status.VERIFIED, r.status()); assertNull(r.source().latitude());
        verify(provider, never()).geocodeUnique("대전 유성구 궁동 1");
    }
    @Test void missingCoordinatesFallBackToJibun() {
        when(provider.geocodeUnique("unknown")).thenReturn(Optional.empty());
        when(provider.geocodeUnique("대전 유성구 궁동 1")).thenReturn(Optional.of(point));
        when(provider.reverse(point)).thenReturn(daejeon);
        Result r = normalizer.normalize(source("unknown", "대전 유성구 궁동 1", null));
        assertEquals("JIBUN", r.fallback()); assertEquals(Status.VERIFIED, r.status());
    }
    @Test void roadRequestFailureAllowsJibunAttempt() {
        when(provider.geocodeUnique("unknown")).thenThrow(new RegionProvider.Failure("KAKAO_NETWORK_FAILURE"));
        when(provider.geocodeUnique("대전 유성구 궁동 1")).thenReturn(Optional.of(point));
        when(provider.reverse(point)).thenReturn(daejeon);
        assertEquals("JIBUN", normalizer.normalize(source("unknown", "대전 유성구 궁동 1", null)).fallback());
    }
    @Test void noCoordinatesOrAddressesProducesExplicitReviewResult() {
        Result r = normalizer.normalize(source(null, null, null));
        assertEquals(Status.NO_COORDINATE, r.status()); assertNull(r.region()); verifyNoInteractions(provider);
    }
    @Test void invalidAndPartialCoordinatesAreNotReplaced() {
        for (Point invalid : new Point[]{new Point(BigDecimal.ZERO, BigDecimal.ONE), new Point(BigDecimal.ONE, null)}) {
            assertEquals(Status.INVALID_COORDINATE, normalizer.normalize(source("대전 유성구", null, invalid)).status());
        }
        verifyNoInteractions(provider);
    }
    @Test void providerFailuresAreReportedButQuotaStopsTheRun() {
        when(provider.reverse(point)).thenThrow(new RegionProvider.Failure("NO_LEGAL_REGION"));
        assertEquals(Status.REVERSE_FAILED, normalizer.normalize(source(null, null, point)).status());
        doThrow(new RegionProvider.Stop("QUOTA")).when(provider).reverse(point);
        assertThrows(RegionProvider.Stop.class, () -> normalizer.normalize(source(null, null, point)));
    }
    static Stream<Arguments> jurisdictions() {
        return Stream.of(
                Arguments.of("서울특별시", "11", "강남구", "11680", null, null, "서울 강남구 테헤란로 1"),
                Arguments.of("대전광역시", "30", "유성구", "30200", null, null, "대전 유성구 대학로 99"),
                Arguments.of("충청남도", "44", "공주시", "44150", null, null, "충남 공주시 번영로 1"),
                Arguments.of("전라남도", "46", "함평군", "46860", null, null, "전남 함평군 함평읍 1"),
                Arguments.of("충청남도", "44", "천안시 서북구", "44133", "천안시", "서북구", "충남 천안시 서북구 불당대로 1"),
                Arguments.of("경기도", "41", "수원시 영통구", "41117", "수원시", "영통구", "경기 수원시 영통구 광교로 1"),
                Arguments.of("세종특별자치시", "36", null, "36110", null, null, "세종특별자치시 한누리대로 1"));
    }
    @ParameterizedTest @MethodSource("jurisdictions")
    void administrativeStructures(String sido, String code, String sigungu, String sigunguCode, String city, String district, String address) {
        Region region = new Region(sido, code, sigungu, sigunguCode, city, district, sigunguCode + "12345", null);
        when(provider.reverse(point)).thenReturn(region);
        Result r = normalizer.normalize(source(address, null, point));
        assertEquals(Status.VERIFIED, r.status()); assertEquals(sigungu, r.region().sigunguName());
    }
    @Test void cityWithoutDistrictCannotCountAsFullMatch() {
        Region region = new Region("경기도", "41", "수원시 영통구", "41117", "수원시", "영통구", "4111710100", null);
        var check = new AddressRegionCheck();
        assertEquals(Check.UNKNOWN, check.check("경기 수원시 광교로 1", region));
        assertEquals(Check.MISMATCH, check.check("경기 수원시 장안구 1", region));
        assertEquals(Check.UNKNOWN, check.check("광교로 1", region));
    }
    static Source source(String road, String jibun, Point p) { return new Source(1, road, jibun, p == null ? null : p.latitude(), p == null ? null : p.longitude()); }
}
