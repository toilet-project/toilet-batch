package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Provider B (legal-dong) code system, never mixed with H administrative-dong codes. */
public final class RegionModel {
    private RegionModel() { }
    public static final String VERSION = "kakao-b-v2";

    public record Point(BigDecimal latitude, BigDecimal longitude) {
        public boolean valid() {
            return latitude != null && longitude != null && latitude.abs().compareTo(new BigDecimal("90")) <= 0
                    && longitude.abs().compareTo(new BigDecimal("180")) <= 0
                    && latitude.signum() != 0 && longitude.signum() != 0;
        }
        public Point rounded() {
            return new Point(latitude.setScale(7, RoundingMode.HALF_UP), longitude.setScale(7, RoundingMode.HALF_UP));
        }
        public String key() { return latitude.stripTrailingZeros().toPlainString() + ":" + longitude.stripTrailingZeros().toPlainString(); }
    }
    public record Source(long toiletId, String roadAddress, String jibunAddress, BigDecimal latitude, BigDecimal longitude) {
        public Point point() { return new Point(latitude, longitude); }
        public boolean missing() { return latitude == null || longitude == null; }
        public Source withPoint(Point p) { return new Source(toiletId, roadAddress, jibunAddress, p.latitude(), p.longitude()); }
        public String hash() {
            try {
                // Length framing distinguishes null, empty, separators and genuine address changes.
                String input = VERSION + frame(roadAddress) + frame(jibunAddress)
                        + frame(latitude == null ? null : latitude.stripTrailingZeros().toPlainString())
                        + frame(longitude == null ? null : longitude.stripTrailingZeros().toPlainString());
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        private static String frame(String value) { return value == null ? "-1:" : value.length() + ":" + value; }
    }
    public record Region(String sidoName, String sidoCode, String sigunguName, String sigunguCode,
                         String cityName, String districtName, String legalDongCode, String administrativeDongCode) { }
    public enum Check { MATCH, MISMATCH, UNKNOWN }
    public enum Status { VERIFIED, MISMATCH, ADDRESS_UNVERIFIED, NO_COORDINATE, INVALID_COORDINATE, REVERSE_FAILED }
    public record AddressLookup(int totalCount, Point point, String legalDongCode, String roadAddress, String jibunAddress) { }
    public record ReverseAddress(String sidoName, String sigunguName, String roadAddress, String jibunAddress,
                                 String roadSidoName, String roadSigunguName) {
        public ReverseAddress(String sidoName, String sigunguName, String roadAddress, String jibunAddress) {
            this(sidoName, sigunguName, roadAddress, jibunAddress, roadAddress == null ? null : sidoName, roadAddress == null ? null : sigunguName);
        }
    }
    public record AddressEvidence(String field, AddressLookup lookup, Double distanceMeters, String reason) { }
    public record Evidence(ReverseAddress reverseAddress, double maxDistanceMeters,
                           java.util.List<AddressEvidence> addresses, java.util.List<String> reasons) {
        public Evidence {
            addresses = java.util.List.copyOf(addresses);
            reasons = java.util.List.copyOf(reasons);
        }
    }
    public record Result(Source source, Point evaluated, Region region, Status status, String reason,
                         Check roadCheck, Check jibunCheck, String fallback, long checkedEpochMillis, String algorithmVersion,
                         Evidence evidence) {
        public Result(Source source, Point evaluated, Region region, Status status, String reason,
                      Check roadCheck, Check jibunCheck, String fallback, long checkedEpochMillis, String algorithmVersion) {
            this(source, evaluated, region, status, reason, roadCheck, jibunCheck, fallback, checkedEpochMillis, algorithmVersion, null);
        }
        public boolean retryable() { return status == Status.REVERSE_FAILED; }
        public Result withSource(Source replacement) {
            return new Result(replacement, evaluated, region, status, reason, roadCheck, jibunCheck, fallback, checkedEpochMillis, algorithmVersion, evidence);
        }
    }
}
