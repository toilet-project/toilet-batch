package com.example.toiletbatch.publicdata;

import java.math.BigDecimal;

/**
 * 공공데이터포털 공중화장실 API 레코드를 적재용 형식으로 정규화한 값입니다.
 * DB Upsert는 다음 배치 단계에서 이 DTO를 입력으로 사용합니다.
 */
public record PublicRestroomRecord(
        String managementNumber,
        String name,
        String toiletType,
        String ownershipType,
        String roadAddress,
        String jibunAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer maleToiletCount,
        Integer maleUrinalCount,
        Integer maleDisabledToiletCount,
        Integer maleDisabledUrinalCount,
        Integer maleChildToiletCount,
        Integer maleChildUrinalCount,
        Integer femaleToiletCount,
        Integer femaleDisabledToiletCount,
        Integer femaleChildToiletCount,
        String agencyName,
        String phoneNumber,
        String openTime,
        String openTimeDetail,
        String installationDate,
        String hasEmergencyBell,
        String emergencyBellLocation,
        String hasCctv,
        String hasDiaperTable,
        String diaperTableLocation,
        String dataBaseDate,
        String dataUpdatedAt
) {
}
