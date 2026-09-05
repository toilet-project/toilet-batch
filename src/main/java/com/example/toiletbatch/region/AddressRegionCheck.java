package com.example.toiletbatch.region;

import java.util.List;
import static com.example.toiletbatch.region.RegionModel.*;

/** Address text is corroborating evidence ONLY. Unknown aliases fail closed to manual review. */
public final class AddressRegionCheck {
    private static final List<List<String>> ALIASES = List.of(
            List.of("서울특별시", "서울"), List.of("부산광역시", "부산"), List.of("대구광역시", "대구"),
            List.of("인천광역시", "인천"), List.of("광주광역시", "광주"), List.of("대전광역시", "대전"),
            List.of("울산광역시", "울산"), List.of("세종특별자치시", "세종", "세종시"),
            List.of("경기도", "경기"), List.of("강원특별자치도", "강원도", "강원"),
            List.of("충청북도", "충북"), List.of("충청남도", "충남"),
            List.of("전북특별자치도", "전라북도", "전북"), List.of("전라남도", "전남"),
            List.of("경상북도", "경북"), List.of("경상남도", "경남"), List.of("제주특별자치도", "제주도", "제주"));

    public Check check(String address, Region region) {
        if (address == null || address.isBlank()) return Check.UNKNOWN;
        String text = address.strip().replaceAll("(?U)\\s+", " ").strip();
        String first = text.split(" ", 2)[0];
        String canonical = ALIASES.stream().filter(a -> a.contains(first)).map(List::getFirst).findFirst().orElse(first);
        String expected = ALIASES.stream().filter(a -> a.contains(region.sidoName())).map(List::getFirst).findFirst().orElse(region.sidoName());
        if (!canonical.equals(expected)) {
            boolean knownProvince = ALIASES.stream().anyMatch(a -> a.contains(first))
                    || first.matches(".+(특별시|광역시|특별자치시|특별자치도|도)");
            return knownProvince ? Check.MISMATCH : Check.UNKNOWN;
        }
        if (region.sigunguName() == null) return "36".equals(region.sidoCode()) ? Check.MATCH : Check.UNKNOWN;
        String rest = text.length() > first.length() ? text.substring(first.length()).strip() : "";
        String sigungu = region.sigunguName();
        if (rest.equals(sigungu) || rest.startsWith(sigungu + " ")) return Check.MATCH;
        // Full district evidence is needed; a matching parent city alone is not a full match.
        if (region.cityName() != null && (rest.equals(region.cityName()) || rest.startsWith(region.cityName() + " "))) {
            String district = rest.substring(region.cityName().length()).strip().split(" ", 2)[0];
            return district.endsWith("구") ? Check.MISMATCH : Check.UNKNOWN;
        }
        String candidate = rest.split(" ", 2)[0];
        return candidate.matches(".+[시군구]") ? Check.MISMATCH : Check.UNKNOWN;
    }
}
