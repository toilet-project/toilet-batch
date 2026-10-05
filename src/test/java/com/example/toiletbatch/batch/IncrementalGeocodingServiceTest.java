package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.toiletbatch.geocoding.Coordinate;
import com.example.toiletbatch.geocoding.KakaoAddressGeocodingClient;
import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncrementalGeocodingServiceTest {

    @Mock
    private ToiletCoordinateMetadataRepository metadataRepository;

    @Mock
    private KakaoAddressGeocodingClient geocodingClient;

    @Test
    void geocodesNewRecordWithRoadAddressFirst() {
        when(metadataRepository.findAllByManagementNumbers(List.of("A"))).thenReturn(Map.of());
        when(geocodingClient.geocode("대전 유성구 대학로 99"))
                .thenReturn(Optional.of(new Coordinate(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"))));

        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "대전 유성구 대학로 99", "궁동 1"))).getFirst();

        assertEquals("GEOCODED_ROAD", result.coordinateSource());
        assertEquals(new BigDecimal("36.3620000"), result.latitude());
        verify(geocodingClient, never()).geocode("궁동 1");
    }

    @Test
    void fallsBackToJibunAddressWhenRoadAddressHasNoResult() {
        when(metadataRepository.findAllByManagementNumbers(List.of("A"))).thenReturn(Map.of());
        when(geocodingClient.geocode("도로명 없음")).thenReturn(Optional.empty());
        when(geocodingClient.geocode("궁동 1"))
                .thenReturn(Optional.of(new Coordinate(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"))));

        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "도로명 없음", "궁동 1"))).getFirst();

        assertEquals("GEOCODED_JIBUN", result.coordinateSource());
    }

    @Test
    void keepsAdministratorConfirmedCoordinateWithoutExternalCall() {
        CoordinateMetadata confirmed = new CoordinateMetadata(
                "기존 도로명", "기존 지번", new BigDecimal("35.0000000"), new BigDecimal("127.0000000"),
                "ADMIN_CONFIRMED", "a".repeat(64), null
        );
        when(metadataRepository.findAllByManagementNumbers(List.of("A"))).thenReturn(Map.of("A", confirmed));

        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "변경 도로명", "변경 지번"))).getFirst();

        assertEquals("ADMIN_CONFIRMED", result.coordinateSource());
        assertEquals(new BigDecimal("35.0000000"), result.latitude());
        verify(geocodingClient, never()).geocode(anyString());
    }

    @Test
    void addressOnlyEditDoesNotMoveExistingCoordinate() {
        CoordinateMetadata existing = new CoordinateMetadata(
                "기존 주소", "기존 지번", new BigDecimal("35.0000000"), new BigDecimal("127.0000000"),
                "GEOCODED_LEGACY", "a".repeat(64), null);
        when(metadataRepository.findAllByManagementNumbers(List.of("A"))).thenReturn(Map.of("A", existing));
        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "주소 오타 수정", "기존 지번"))).getFirst();
        assertEquals(existing.latitude(), result.latitude());
        assertEquals(existing.longitude(), result.longitude());
        verify(geocodingClient, never()).geocode(anyString());
    }

    @Test
    void productionClockRecordsGeocodingTimeInConfiguredZone() {
        when(metadataRepository.findAllByManagementNumbers(List.of("KST"))).thenReturn(Map.of());
        when(geocodingClient.geocode("대전 유성구 대학로 99"))
                .thenReturn(Optional.of(new Coordinate(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"))));
        RestroomSyncProperties properties = new RestroomSyncProperties(null, "Asia/Seoul", 3, 3);
        IncrementalGeocodingService service = new IncrementalGeocodingService(
                metadataRepository, geocodingClient, properties
        );
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Seoul"));

        ResolvedRestroomRecord result = service.resolveAll(
                List.of(record("KST", "대전 유성구 대학로 99", "궁동 1"))
        ).getFirst();

        LocalDateTime after = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        assertFalse(result.geocodedAt().isBefore(before));
        assertFalse(result.geocodedAt().isAfter(after));
    }

    @Test
    void preservesPageOrderDuplicatesPartialCoordinatesAndEmptyKeys() {
        CoordinateMetadata partial = new CoordinateMetadata(
                "기존 주소", null, new BigDecimal("35.0000000"), null, "GEOCODED_LEGACY", "old", null);
        CoordinateMetadata confirmed = new CoordinateMetadata(
                null, null, null, null, "ADMIN_CONFIRMED", "confirmed", null);
        List<PublicRestroomRecord> records = List.of(record("A", "변경 주소", "지번"),
                record(null, "호출 금지", ""), record("C", "호출 금지", ""),
                record("A", "다른 변경 주소", ""), record(" \t", "호출 금지", ""));
        when(metadataRepository.findAllByManagementNumbers(Arrays.asList("A", null, "C", "A", " \t")))
                .thenReturn(Map.of("A", partial, "C", confirmed));

        List<ResolvedRestroomRecord> results = service().resolveAll(records);

        assertEquals(records, results.stream().map(ResolvedRestroomRecord::restroom).toList());
        assertEquals(partial.latitude(), results.get(0).latitude());
        assertNull(results.get(0).longitude());
        assertEquals("GEOCODED_LEGACY", results.get(3).coordinateSource());
        assertEquals("ADMIN_CONFIRMED", results.get(2).coordinateSource());
        assertNull(results.get(1).coordinateSource());
        assertNull(results.get(4).coordinateSource());
        verifyNoInteractions(geocodingClient);
    }

    @Test
    void continuesPageAfterGeocoderFailureAndPreservesAddressCallOrder() {
        when(metadataRepository.findAllByManagementNumbers(List.of("FAIL", "NEXT"))).thenReturn(Map.of());
        when(geocodingClient.geocode("실패 도로")).thenThrow(new IllegalStateException("synthetic failure"));
        when(geocodingClient.geocode("다음 도로")).thenReturn(Optional.empty());
        when(geocodingClient.geocode("다음 지번"))
                .thenReturn(Optional.of(new Coordinate(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"))));

        List<ResolvedRestroomRecord> results = service().resolveAll(List.of(
                record("FAIL", "실패 도로", "실패 지번"), record("NEXT", "다음 도로", "다음 지번")));

        assertEquals("GEOCODE_FAILED", results.get(0).coordinateSource());
        assertEquals(LocalDateTime.of(2026, 8, 26, 2, 0), results.get(0).geocodedAt());
        assertEquals("GEOCODED_JIBUN", results.get(1).coordinateSource());
        var order = inOrder(geocodingClient);
        order.verify(geocodingClient).geocode("실패 도로");
        order.verify(geocodingClient).geocode("다음 도로");
        order.verify(geocodingClient).geocode("다음 지번");
        order.verifyNoMoreInteractions();
    }

    private IncrementalGeocodingService service() {
        return new IncrementalGeocodingService(
                metadataRepository,
                geocodingClient,
                Clock.fixed(Instant.parse("2026-08-26T02:00:00Z"), ZoneOffset.UTC)
        );
    }

    private PublicRestroomRecord record(String managementNumber, String roadAddress, String jibunAddress) {
        return new PublicRestroomRecord(
                managementNumber, "테스트", "개방", "공중", roadAddress, jibunAddress, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                "기관", "전화", "상시", "", "", "", "", "", "", "", "", ""
        );
    }
}
