package com.example.toiletbatch.publicdata;

import java.util.List;

public record PublicRestroomPage(
        List<PublicRestroomRecord> records,
        int pageNumber,
        int pageSize,
        long totalCount
) {
}
