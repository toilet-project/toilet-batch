package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionRecheckTest {
    final RegionProvider provider = mock(RegionProvider.class);
    final Point point = new Point(new BigDecimal("37.5"), new BigDecimal("127.0"));
    final Region region = new Region("서울특별시", "11", "용산구", "11170", null, null, "1117010100", null);
    final Source source = new Source(380, "서울특별시 용산구녹사평대로11길 24", null, point.latitude(), point.longitude());
    final RegionNormalizer normalizer = new RegionNormalizer(provider, Clock.systemUTC());

    void matching() {
        when(provider.reverse(point)).thenReturn(region);
        when(provider.reverseAddress(point)).thenReturn(new ReverseAddress("서울", "용산구", "서울 용산구 녹사평대로11길 24", null));
        when(provider.searchAddress(anyString())).thenReturn(new AddressLookup(1, point, "1117010100", "도로명", "지번"));
    }
    @Test void gluedAddressNeedsStructuredEvidenceAndPreservesOriginal() {
        matching(); Result result = normalizer.normalize(source);
        assertEquals(Status.VERIFIED, result.status());
        assertEquals("STRUCTURED_ADDRESS_CORROBORATED", result.reason());
        assertEquals(source, result.source()); assertEquals(Check.UNKNOWN, result.roadCheck());
        assertEquals(0, result.evidence().addresses().getFirst().distanceMeters());
        verify(provider, never()).geocodeUnique(anyString());
    }
    @Test void zeroMultipleWrongCodeAndDistanceRemainReviewable() {
        matching();
        for (AddressLookup lookup : new AddressLookup[]{
                new AddressLookup(0, null, null, null, null), new AddressLookup(3, null, null, null, null),
                new AddressLookup(1, point, "1168010100", null, null),
                new AddressLookup(1, new Point(new BigDecimal("37.51"), point.longitude()), "1117010100", null, null),
                new AddressLookup(1, point, "11170", null, null)}) {
            when(provider.searchAddress(anyString())).thenReturn(lookup);
            Result result = normalizer.normalize(source);
            assertEquals(Status.ADDRESS_UNVERIFIED, result.status());
            assertFalse(result.evidence().reasons().isEmpty()); assertEquals(source, result.source());
        }
    }
    @Test void bothNonblankAddressesMustPassEvenWhenOneMatches() {
        matching();
        when(provider.searchAddress("parcel")).thenReturn(new AddressLookup(0, null, null, null, null));
        Result result = normalizer.normalize(new Source(1, source.roadAddress(), "parcel", point.latitude(), point.longitude()));
        assertEquals(Status.ADDRESS_UNVERIFIED, result.status()); assertEquals(2, result.evidence().addresses().size());
    }
    @Test void yudeungStyleProviderDisagreementNeverPromotes() {
        matching();
        when(provider.reverseAddress(point)).thenReturn(new ReverseAddress("서울", "강남구", null, "서울 강남구"));
        Result result = normalizer.normalize(source);
        assertEquals(Status.ADDRESS_UNVERIFIED, result.status());
        assertTrue(result.evidence().reasons().contains("PROVIDER_REGION_DISAGREEMENT"));
    }
    @Test void reverseRoadAndParcelDisagreementIsManualNotTransportFailure() {
        matching();
        when(provider.reverseAddress(point)).thenReturn(new ReverseAddress("서울", "용산구", "서울 강남구 도로", "서울 용산구 지번", "서울", "강남구"));
        Result result = normalizer.normalize(source);
        assertEquals(Status.ADDRESS_UNVERIFIED, result.status()); assertFalse(result.retryable());
        assertTrue(result.evidence().reasons().contains("REVERSE_ROAD_REGION_DISAGREEMENT"));
    }
    @Test void oldRegionNameRequiresCodeAndProximityNotAliasReplacement() {
        matching();
        Source old = new Source(1, "서울 강남구 구주소", null, point.latitude(), point.longitude());
        Result result = normalizer.normalize(old);
        assertEquals(Check.MISMATCH, result.roadCheck()); assertEquals(Status.VERIFIED, result.status());
        when(provider.searchAddress(anyString())).thenReturn(new AddressLookup(1, point, "1168010100", null, null));
        assertEquals(Status.MISMATCH, normalizer.normalize(old).status());
    }
    @Test void transientFailureKeepsRegionAndPartialEvidenceAndQuotaAborts() {
        matching();
        when(provider.searchAddress(anyString())).thenThrow(new RegionProvider.Failure("KAKAO_NETWORK_FAILURE"));
        Result result = normalizer.normalize(source);
        assertEquals(Status.REVERSE_FAILED, result.status()); assertTrue(result.retryable());
        assertEquals(region, result.region()); assertNotNull(result.evidence().reverseAddress());
        doThrow(new RegionProvider.Stop("QUOTA")).when(provider).searchAddress(anyString());
        assertThrows(RegionProvider.Stop.class, () -> normalizer.normalize(source));
    }
    @Test void distanceThresholdIsConservativeAndVersionInvalidatesOldCheckpoint() {
        matching(); Result r = normalizer.normalize(source);
        Result old = new Result(source, point, region, r.status(), r.reason(), r.roadCheck(), r.jibunCheck(), "NONE", r.checkedEpochMillis(), "kakao-b-v1");
        assertFalse(RegionJob.fresh(old, source, r.checkedEpochMillis()));
        assertEquals(50, r.evidence().maxDistanceMeters());
        assertEquals(Check.MATCH, new AddressRegionCheck().check("서울\u00a0용산구\t주소", region));
    }
}
