package com.example.toiletbatch.openinghours;

import java.time.LocalTime;
import java.util.List;

public final class OpeningHoursModels {
    private OpeningHoursModels() {}

    public record Slot(
            int dayOfWeek,
            int slotIndex,
            LocalTime startTime,
            LocalTime endTime,
            boolean crossesMidnight,
            boolean closed
    ) {}

    public record Normalized(
            String openingPolicy,
            Boolean open24h,
            String status,
            Double confidence,
            String holidayPolicy,
            List<Slot> schedules
    ) {
        public Normalized {
            schedules = List.copyOf(schedules);
        }
    }

    public record View(
            String openingPolicy,
            Boolean open24h,
            String status,
            Double confidence,
            String parserVersion,
            String holidayPolicy,
            boolean manualOverride,
            boolean sourceChanged,
            List<Slot> schedules
    ) {
        public View {
            schedules = List.copyOf(schedules);
        }
    }

    public record BackfillResult(long lastToiletId, int normalizedCount, boolean hasMore) {}
}
