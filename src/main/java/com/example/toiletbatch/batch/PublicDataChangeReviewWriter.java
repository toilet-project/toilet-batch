package com.example.toiletbatch.batch;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Records protected public-data inputs before the normal upsert hides them behind admin-confirmed values. */
@Repository
class PublicDataChangeReviewWriter {
    enum Capture { RECORDED, NOT_PROTECTED, DUPLICATE, CONFLICT }

    private final JdbcTemplate jdbc;

    PublicDataChangeReviewWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Capture capture(String executionKey, ResolvedRestroomRecord resolved) {
        PublicRestroomRecord proposal = resolved.restroom();
        Optional<Snapshot> existing = snapshot(proposal.managementNumber());
        if (existing.isEmpty() || !"ADMIN_CONFIRMED".equals(existing.get().coordinateSource()))
            return Capture.NOT_PROTECTED;

        Snapshot current = existing.get();
        String baselineHash = hash(current.latitude(), current.longitude(), current.roadAddress(), current.jibunAddress());
        String proposalHash = hash(proposal.latitude(), proposal.longitude(), proposal.roadAddress(), proposal.jibunAddress());
        Optional<ExistingReceipt> priorReceipt = receipt(executionKey, current.toiletId());
        if (priorReceipt.isPresent()) {
            if (priorReceipt.get().inputHash().equals(proposalHash)) return Capture.DUPLICATE;
            markConflict(executionKey, current.toiletId(), priorReceipt.get().reviewId(), baselineHash, proposalHash);
            return Capture.CONFLICT;
        }

        if (baselineHash.equals(proposalHash)) {
            supersedeActive(current.toiletId(), "PUBLIC_DATA_MATCH", null);
            insertReceipt(executionKey, current.toiletId(), null, proposalHash, baselineHash,
                    baselineHash, "MATCHED_CURRENT");
            return Capture.RECORDED;
        }

        ExistingReview reusable = reusable(current.toiletId(), baselineHash, proposalHash).orElse(null);
        Long reviewId;
        String result;
        if (reusable != null && ("PENDING".equals(reusable.status()) || "KEPT_CURRENT".equals(reusable.status()))) {
            if ("KEPT_CURRENT".equals(reusable.status()))
                supersedeActive(current.toiletId(), "PREVIOUS_DECISION_REUSED", null);
            jdbc.update("""
                    UPDATE public_data_change_review
                       SET last_received_at=CURRENT_TIMESTAMP,receipt_count=receipt_count+1,
                           provider_updated_at=COALESCE(?,provider_updated_at),updated_at=CURRENT_TIMESTAMP
                     WHERE review_id=?
                    """, providerUpdatedAt(proposal.dataUpdatedAt()), reusable.id());
            reviewId = reusable.id();
            result = "KEPT_CURRENT".equals(reusable.status()) ? "KEPT_CURRENT" : "CHANGE_CANDIDATE";
        } else {
            List<Long> old = activeReviewIds(current.toiletId());
            supersedeActive(current.toiletId(), "NEW_PUBLIC_DATA_PROPOSAL", null);
            jdbc.update("""
                    INSERT INTO public_data_change_review
                        (toilet_id,active_toilet_id,baseline_latitude,baseline_longitude,
                         baseline_road_address,baseline_jibun_address,proposal_latitude,proposal_longitude,
                         proposal_road_address,proposal_jibun_address,changed_fields,baseline_hash,proposal_hash,
                         provider_updated_at,first_received_at,last_received_at,receipt_count,status,version)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,'PENDING',1)
                    """, current.toiletId(), current.toiletId(), current.latitude(), current.longitude(),
                    current.roadAddress(), current.jibunAddress(), proposal.latitude(), proposal.longitude(),
                    clean(proposal.roadAddress()), clean(proposal.jibunAddress()), changedFields(current, proposal),
                    baselineHash, proposalHash, providerUpdatedAt(proposal.dataUpdatedAt()));
            reviewId = jdbc.queryForObject(
                    "SELECT review_id FROM public_data_change_review WHERE active_toilet_id=?", Long.class, current.toiletId());
            for (Long oldId : old)
                jdbc.update("UPDATE public_data_change_review SET superseded_by_review_id=? WHERE review_id=?", reviewId, oldId);
            result = "CHANGE_CANDIDATE";
        }
        insertReceipt(executionKey, current.toiletId(), reviewId, proposalHash, baselineHash, baselineHash, result);
        return Capture.RECORDED;
    }

    private Optional<Snapshot> snapshot(String managementNumber) {
        List<Snapshot> rows = jdbc.query("""
                SELECT toilet_id,coordinate_source,latitude,longitude,road_address,jibun_address
                  FROM toilet WHERE mng_no=? FOR UPDATE
                """, (rs, row) -> new Snapshot(rs.getLong("toilet_id"), rs.getString("coordinate_source"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("road_address"),
                rs.getString("jibun_address")), managementNumber);
        return rows.stream().findFirst();
    }

    private Optional<ExistingReceipt> receipt(String executionKey, long toiletId) {
        List<ExistingReceipt> rows = jdbc.query("""
                SELECT input_hash,review_id FROM public_data_confirmed_receipt
                 WHERE execution_key=? AND toilet_id=?
                """, (rs, row) -> new ExistingReceipt(rs.getString("input_hash"),
                rs.getObject("review_id", Long.class)), executionKey, toiletId);
        return rows.stream().findFirst();
    }

    private Optional<ExistingReview> reusable(long toiletId, String baselineHash, String proposalHash) {
        List<ExistingReview> rows = jdbc.query("""
                SELECT review_id,status FROM public_data_change_review
                 WHERE toilet_id=? AND baseline_hash=? AND proposal_hash=?
                 ORDER BY review_id DESC LIMIT 1
                """, (rs, row) -> new ExistingReview(rs.getLong("review_id"), rs.getString("status")),
                toiletId, baselineHash, proposalHash);
        return rows.stream().findFirst();
    }

    private List<Long> activeReviewIds(long toiletId) {
        return jdbc.query("SELECT review_id FROM public_data_change_review WHERE active_toilet_id=?",
                (rs, row) -> rs.getLong(1), toiletId);
    }

    private void supersedeActive(long toiletId, String reason, Long replacementId) {
        jdbc.update("""
                UPDATE public_data_change_review
                   SET status='SUPERSEDED',status_reason=?,active_toilet_id=NULL,
                       superseded_by_review_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP
                 WHERE active_toilet_id=? AND status='PENDING'
                """, reason, replacementId, toiletId);
    }

    private void markConflict(String executionKey, long toiletId, Long reviewId,
                              String baselineHash, String incomingHash) {
        if (reviewId != null)
            jdbc.update("""
                    UPDATE public_data_change_review SET status='SUPERSEDED',status_reason='BATCH_INPUT_CONFLICT',
                           active_toilet_id=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP
                     WHERE review_id=? AND status='PENDING'
                    """, reviewId);
        supersedeActive(toiletId, "BATCH_INPUT_CONFLICT", null);
        jdbc.update("""
                UPDATE public_data_confirmed_receipt
                   SET result='CONFLICT',input_hash=?,protected_after_hash=?,received_at=CURRENT_TIMESTAMP
                 WHERE execution_key=? AND toilet_id=?
                """, incomingHash, baselineHash, executionKey, toiletId);
    }

    private void insertReceipt(String executionKey, long toiletId, Long reviewId, String inputHash,
                               String beforeHash, String afterHash, String result) {
        jdbc.update("""
                INSERT INTO public_data_confirmed_receipt
                    (execution_key,toilet_id,review_id,received_at,input_hash,
                     protected_before_hash,protected_after_hash,result)
                VALUES (?,?,?,CURRENT_TIMESTAMP,?,?,?,?)
                """, executionKey, toiletId, reviewId, inputHash, beforeHash, afterHash, result);
    }

    static String hash(BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress) {
        String value = coordinate(latitude) + "\u001f" + coordinate(longitude) + "\u001f"
                + normalize(roadAddress) + "\u001f" + normalize(jibunAddress);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private static String changedFields(Snapshot current, PublicRestroomRecord proposal) {
        List<String> fields = new ArrayList<>();
        if (!coordinate(current.latitude()).equals(coordinate(proposal.latitude()))) fields.add("LATITUDE");
        if (!coordinate(current.longitude()).equals(coordinate(proposal.longitude()))) fields.add("LONGITUDE");
        if (!normalize(current.roadAddress()).equals(normalize(proposal.roadAddress()))) fields.add("ROAD_ADDRESS");
        if (!normalize(current.jibunAddress()).equals(normalize(proposal.jibunAddress()))) fields.add("JIBUN_ADDRESS");
        return String.join(",", fields);
    }

    private static Timestamp providerUpdatedAt(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            if (text.matches("\\d{14}"))
                return Timestamp.valueOf(LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            if (text.matches("\\d{8}"))
                return Timestamp.valueOf(LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay());
            return Timestamp.valueOf(LocalDateTime.parse(text));
        } catch (DateTimeParseException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String coordinate(BigDecimal value) {
        return value == null ? "" : value.setScale(7, RoundingMode.HALF_UP).toPlainString();
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private record Snapshot(long toiletId, String coordinateSource, BigDecimal latitude, BigDecimal longitude,
                            String roadAddress, String jibunAddress) {}
    private record ExistingReceipt(String inputHash, Long reviewId) {}
    private record ExistingReview(long id, String status) {}
}
