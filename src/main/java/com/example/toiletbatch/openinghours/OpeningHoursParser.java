package com.example.toiletbatch.openinghours;

import static com.example.toiletbatch.openinghours.OpeningHoursModels.Normalized;
import static com.example.toiletbatch.openinghours.OpeningHoursModels.Slot;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class OpeningHoursParser {
    public static final String VERSION = "v1";
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])(?:[:시]([0-5]\\d)?)?\\s*(?:~|〜|～|–|—|-)\\s*"
                    + "([01]?\\d|2[0-4])(?:[:시]([0-5]\\d)?)?(?!\\d)");
    private static final Pattern EXPLICIT_24H = Pattern.compile(
            "(?<!\\d)(?:24\\s*시간|00(?::00)?\\s*(?:~|〜|～|–|—|-)\\s*(?:24(?::00)?|23:59))(?!\\d)");
    private static final Pattern CLOSED = Pattern.compile("미개방|폐쇄|운영\\s*안함|이용\\s*불가");
    private static final Pattern EXCEPTION = Pattern.compile("공휴일\\s*(?:제외|휴무)|휴관|동절기|하절기|계절|임시|주말\\s*제외");

    public Normalized parse(String openTime, String openTimeDetail) {
        String statusText = clean(openTime);
        String detail = clean(openTimeDetail);
        String combined = (statusText + " " + detail).trim();
        if (combined.isEmpty()) return review("UNKNOWN", null, "UNKNOWN");

        boolean saysClosed = CLOSED.matcher(combined).find() || "미개방".equals(statusText);
        boolean says24h = explicit24h(combined);
        boolean hasException = EXCEPTION.matcher(combined).find();
        List<ParsedRange> ranges = ranges(detail.isEmpty() ? statusText : detail);

        if ((saysClosed && (says24h || !ranges.isEmpty())) || (says24h && hasException)) {
            return review("UNKNOWN", null, holidayPolicy(combined));
        }
        if (saysClosed) return parsed("CLOSED", false, 0.95, holidayPolicy(combined), List.of());
        if (says24h && ranges.stream().allMatch(ParsedRange::fullDay)) {
            return parsed("ALWAYS", true, 1.0, holidayPolicy(combined), List.of());
        }
        if (says24h && ranges.isEmpty()) {
            return parsed("ALWAYS", true, 1.0, holidayPolicy(combined), List.of());
        }
        if ("불규칙".equals(statusText)) return review("IRREGULAR", null, holidayPolicy(combined));

        List<ParsedRange> limitedRanges = ranges.stream().filter(range -> !range.fullDay()).toList();
        if (limitedRanges.stream().anyMatch(range -> range.start().equals(range.end()))) {
            return review("UNKNOWN", null, holidayPolicy(combined));
        }
        if (limitedRanges.size() == 1 && !hasException) {
            ParsedRange range = limitedRanges.getFirst();
            Set<Integer> days = days(combined);
            List<Slot> slots = days.stream()
                    .map(day -> new Slot(day, 0, range.start(), range.end(), range.crossesMidnight(), false))
                    .toList();
            return parsed("SCHEDULED", false, days.size() == 7 ? 0.90 : 0.85,
                    holidayPolicy(combined), slots);
        }
        if ("연중무휴".equals(detail) || "연중무휴".equals(statusText)) {
            return review("ALWAYS", null, "OPEN");
        }
        return review("UNKNOWN", null, holidayPolicy(combined));
    }

    private static boolean explicit24h(String value) {
        String normalized = value.replace("00~24", "00:00~24:00");
        return EXPLICIT_24H.matcher(normalized).find();
    }

    private static List<ParsedRange> ranges(String value) {
        List<ParsedRange> result = new ArrayList<>();
        Matcher matcher = TIME_RANGE.matcher(value);
        while (matcher.find()) {
            int startHour = number(matcher.group(1));
            int startMinute = numberOrZero(matcher.group(2));
            int endHour = number(matcher.group(3));
            int endMinute = numberOrZero(matcher.group(4));
            if (endHour == 24 && endMinute != 0) continue;
            boolean fullDay = startHour == 0 && startMinute == 0
                    && ((endHour == 24 && endMinute == 0) || (endHour == 23 && endMinute == 59));
            LocalTime start = LocalTime.of(startHour, startMinute);
            LocalTime end = endHour == 24 ? LocalTime.MIDNIGHT : LocalTime.of(endHour, endMinute);
            boolean crossesMidnight = !fullDay && !end.isAfter(start);
            result.add(new ParsedRange(start, end, crossesMidnight, fullDay));
        }
        return result;
    }

    private static Set<Integer> days(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "평일", "월~금", "월-금", "월요일~금요일", "월요일-금요일")) {
            return ordered(1, 2, 3, 4, 5);
        }
        if (containsAny(value, "주말", "토~일", "토-일", "토요일~일요일", "토요일-일요일")) {
            return ordered(6, 7);
        }
        LinkedHashSet<Integer> specific = new LinkedHashSet<>();
        String[] names = {"월", "화", "수", "목", "금", "토", "일"};
        for (int index = 0; index < names.length; index++) {
            if (value.contains(names[index] + "요일")) specific.add(index + 1);
        }
        return specific.isEmpty() ? ordered(1, 2, 3, 4, 5, 6, 7) : specific;
    }

    private static String holidayPolicy(String value) {
        if (value.contains("연중무휴")) return "OPEN";
        if (value.matches(".*공휴일\\s*(제외|휴무).*")) return "CLOSED";
        return "UNKNOWN";
    }

    private static Normalized parsed(String policy, Boolean open24h, double confidence,
                                     String holidayPolicy, List<Slot> slots) {
        return new Normalized(policy, open24h, "PARSED", confidence, holidayPolicy, slots);
    }

    private static Normalized review(String policy, Boolean open24h, String holidayPolicy) {
        return new Normalized(policy, open24h, "REVIEW_REQUIRED", null, holidayPolicy, List.of());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static LinkedHashSet<Integer> ordered(Integer... days) {
        return new LinkedHashSet<>(List.of(days));
    }

    private static int number(String value) { return Integer.parseInt(value); }
    private static int numberOrZero(String value) { return value == null || value.isBlank() ? 0 : number(value); }

    private record ParsedRange(LocalTime start, LocalTime end, boolean crossesMidnight, boolean fullDay) {}
}
