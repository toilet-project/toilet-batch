package com.example.toiletbatch.batch;

import java.math.BigDecimal;
import java.time.LocalDateTime;

record CoordinateMetadata(
        String roadAddress,
        String jibunAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String source,
        String addressHash,
        LocalDateTime geocodedAt
) {
}
