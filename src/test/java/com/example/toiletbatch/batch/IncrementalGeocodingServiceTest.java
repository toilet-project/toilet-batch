package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(metadataRepository.findByManagementNumber("A")).thenReturn(Optional.empty());
        when(geocodingClient.geocode("대전 유성구 대학로 99"))
                .thenReturn(Optional.of(new Coordinate(new BigDecimal("36.3620000"), new BigDecimal("127.3440000"))));

        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "대전 유성구 대학로 99", "궁동 1"))).getFirst();

        assertEquals("GEOCODED_ROAD", result.coordinateSource());
        assertEquals(new BigDecimal("36.3620000"), result.latitude());
        verify(geocodingClient, never()).geocode("궁동 1");
    }

    @Test
    void fallsBackToJibunAddressWhenRoadAddressHasNoResult() {
        when(metadataRepository.findByManagementNumber("A")).thenReturn(Optional.empty());
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
        when(metadataRepository.findByManagementNumber("A")).thenReturn(Optional.of(confirmed));

        ResolvedRestroomRecord result = service().resolveAll(List.of(record("A", "변경 도로명", "변경 지번"))).getFirst();

        assertEquals("ADMIN_CONFIRMED", result.coordinateSource());
        assertEquals(new BigDecimal("35.0000000"), result.latitude());
        verify(geocodingClient, never()).geocode(anyString());
    }

    @Test
    void productionClockRecordsGeocodingTimeInConfiguredZone() {
        when(metadataRepository.findByManagementNumber("KST")).thenReturn(Optional.empty());
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
