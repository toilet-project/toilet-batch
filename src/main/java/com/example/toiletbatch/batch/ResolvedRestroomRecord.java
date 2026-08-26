package com.example.toiletbatch.batch;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.math.BigDecimal;
import java.time.LocalDateTime;

record ResolvedRestroomRecord(
        PublicRestroomRecord restroom,
        BigDecimal latitude,
        BigDecimal longitude,
        String coordinateSource,
        String geocodedAddressHash,
        LocalDateTime geocodedAt
) {
}
