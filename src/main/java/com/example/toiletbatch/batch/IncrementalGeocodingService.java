package com.example.toiletbatch.batch;

import com.example.toiletbatch.geocoding.Coordinate;
import com.example.toiletbatch.geocoding.KakaoAddressGeocodingClient;
import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;

@Service
class IncrementalGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalGeocodingService.class);
    private static final String ADMIN_CONFIRMED = "ADMIN_CONFIRMED";
    private static final String GEOCODE_FAILED = "GEOCODE_FAILED";

    private final ToiletCoordinateMetadataRepository metadataRepository;
    private final KakaoAddressGeocodingClient geocodingClient;
    private final Clock clock;

    @Autowired
    public IncrementalGeocodingService(
            ToiletCoordinateMetadataRepository metadataRepository,
            KakaoAddressGeocodingClient geocodingClient
    ) {
        this(metadataRepository, geocodingClient, Clock.systemDefaultZone());
    }

    IncrementalGeocodingService(
            ToiletCoordinateMetadataRepository metadataRepository,
            KakaoAddressGeocodingClient geocodingClient,
            Clock clock
    ) {
        this.metadataRepository = metadataRepository;
        this.geocodingClient = geocodingClient;
        this.clock = clock;
    }

    List<ResolvedRestroomRecord> resolveAll(List<PublicRestroomRecord> records) {
        return records.stream().map(this::resolve).toList();
    }

    private ResolvedRestroomRecord resolve(PublicRestroomRecord record) {
        if (!StringUtils.hasText(record.managementNumber())) {
            return new ResolvedRestroomRecord(record, null, null, null, null, null);
        }

        Optional<CoordinateMetadata> metadata = metadataRepository.findByManagementNumber(record.managementNumber());
        if (metadata.filter(value -> ADMIN_CONFIRMED.equals(value.source())).isPresent()) {
            return preserve(record, metadata.orElseThrow());
        }
        if (metadata.isPresent() && !requiresGeocoding(record, metadata.get())) {
            return preserve(record, metadata.get());
        }

        String addressHash = addressHash(record.roadAddress(), record.jibunAddress());
        LocalDateTime attemptedAt = LocalDateTime.now(clock);
        try {
            Optional<Coordinate> roadCoordinate = geocodeIfPresent(record.roadAddress());
            if (roadCoordinate.isPresent()) {
                return resolved(record, roadCoordinate.get(), "GEOCODED_ROAD", addressHash, attemptedAt);
            }
            Optional<Coordinate> jibunCoordinate = geocodeIfPresent(record.jibunAddress());
            if (jibunCoordinate.isPresent()) {
                return resolved(record, jibunCoordinate.get(), "GEOCODED_JIBUN", addressHash, attemptedAt);
            }
        } catch (RuntimeException exception) {
            log.warn("공중화장실 {}의 주소 지오코딩에 실패했습니다: {}", record.managementNumber(), exception.getMessage());
        }

        CoordinateMetadata existing = metadata.orElse(null);
        return new ResolvedRestroomRecord(
                record,
                existing == null ? null : existing.latitude(),
                existing == null ? null : existing.longitude(),
                GEOCODE_FAILED,
                addressHash,
                attemptedAt
        );
    }

    private boolean requiresGeocoding(PublicRestroomRecord record, CoordinateMetadata metadata) {
        if (metadata.latitude() == null || metadata.longitude() == null || GEOCODE_FAILED.equals(metadata.source())) {
            return true;
        }
        return !addressHash(record.roadAddress(), record.jibunAddress())
                .equals(addressHash(metadata.roadAddress(), metadata.jibunAddress()));
    }

    private Optional<Coordinate> geocodeIfPresent(String address) {
        return StringUtils.hasText(address) ? geocodingClient.geocode(address) : Optional.empty();
    }

    private ResolvedRestroomRecord preserve(PublicRestroomRecord record, CoordinateMetadata metadata) {
        return new ResolvedRestroomRecord(
                record, metadata.latitude(), metadata.longitude(), metadata.source(),
                metadata.addressHash(), metadata.geocodedAt()
        );
    }

    private ResolvedRestroomRecord resolved(
            PublicRestroomRecord record,
            Coordinate coordinate,
            String source,
            String addressHash,
            LocalDateTime geocodedAt
    ) {
        return new ResolvedRestroomRecord(
                record, coordinate.latitude(), coordinate.longitude(), source, addressHash, geocodedAt
        );
    }

    private String addressHash(String roadAddress, String jibunAddress) {
        String address = normalize(roadAddress) + "|" + normalize(jibunAddress);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(64);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value)
                ? value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                : "";
    }
}
