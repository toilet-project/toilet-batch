package com.example.toiletbatch.region;

import java.time.Clock;
import java.util.Optional;
import static com.example.toiletbatch.region.RegionModel.*;

/** Shared by backfill and continuous reconciliation. Never mutates source addresses/coordinates. */
public final class RegionNormalizer {
    private final RegionProvider provider;
    private final Clock clock;
    private final AddressRegionCheck checker = new AddressRegionCheck();
    public RegionNormalizer(RegionProvider provider, Clock clock) { this.provider = provider; this.clock = clock; }

    public Result normalize(Source source) {
        Point point = source.point();
        String fallback = "NONE";
        boolean roadFailed = false;
        if (source.missing()) {
            // Partial coordinates are evidence, not a blank slot to overwrite automatically.
            if (source.latitude() != null || source.longitude() != null)
                return failure(source, point, Status.INVALID_COORDINATE, "PARTIAL_COORDINATE", fallback);
            Optional<Point> candidate;
            try {
                try { candidate = forward(source.roadAddress()); }
                catch (RegionProvider.Failure e) { candidate = Optional.empty(); roadFailed = true; }
                fallback = "ROAD";
                if (candidate.isEmpty()) { candidate = forward(source.jibunAddress()); fallback = "JIBUN"; }
            } catch (RegionProvider.Failure e) {
                return failure(source, null, Status.REVERSE_FAILED, "FORWARD_PROVIDER_FAILURE", "NONE");
            }
            if (candidate.isEmpty()) return failure(source, null, roadFailed ? Status.REVERSE_FAILED : Status.NO_COORDINATE,
                    roadFailed ? "FORWARD_ROAD_PROVIDER_FAILURE" : "NO_UNIQUE_ADDRESS_RESULT", "NONE");
            point = candidate.get().rounded();
        }
        if (!point.valid()) return failure(source, point, Status.INVALID_COORDINATE, "INVALID_COORDINATE", fallback);
        try {
            Region region = provider.reverse(point);
            Check road = checker.check(source.roadAddress(), region);
            Check jibun = checker.check(source.jibunAddress(), region);
            Status status = road == Check.MISMATCH || jibun == Check.MISMATCH ? Status.MISMATCH
                    : road == Check.MATCH || jibun == Check.MATCH ? Status.VERIFIED : Status.ADDRESS_UNVERIFIED;
            if (status != Status.VERIFIED && (hasAddress(source.roadAddress()) || hasAddress(source.jibunAddress()))) {
                RegionRecheck.Outcome recheck = new RegionRecheck(provider).check(source, point, region);
                return new Result(source, point, region,
                        recheck.verified() ? Status.VERIFIED : recheck.providerFailed() ? Status.REVERSE_FAILED : status,
                        recheck.verified() ? "STRUCTURED_ADDRESS_CORROBORATED" : recheck.providerFailed() ? "RECHECK_PROVIDER_FAILURE" : "RECHECK_MANUAL_REVIEW",
                        road, jibun, fallback, clock.millis(), VERSION, recheck.evidence());
            }
            return new Result(source, point, region, status,
                    (status == Status.VERIFIED ? "ADDRESS_CORROBORATED" : status == Status.MISMATCH ? "ADDRESS_REGION_CONFLICT" : "INSUFFICIENT_ADDRESS_EVIDENCE")
                            + (roadFailed ? "_AFTER_ROAD_PROVIDER_FAILURE" : ""),
                    road, jibun, fallback, clock.millis(), VERSION);
        } catch (RegionProvider.Failure e) { return failure(source, point, Status.REVERSE_FAILED, e.getMessage(), fallback); }
    }
    private Optional<Point> forward(String address) {
        return address == null || address.isBlank() ? Optional.empty() : provider.geocodeUnique(address);
    }
    private boolean hasAddress(String address) { return address != null && !address.isBlank(); }
    private Result failure(Source source, Point point, Status status, String reason, String fallback) {
        return new Result(source, point, null, status, reason, Check.UNKNOWN, Check.UNKNOWN, fallback, clock.millis(), VERSION);
    }
}
