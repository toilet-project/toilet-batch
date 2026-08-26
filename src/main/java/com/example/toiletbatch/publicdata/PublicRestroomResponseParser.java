package com.example.toiletbatch.publicdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PublicRestroomResponseParser {

    private final ObjectMapper objectMapper;

    public PublicRestroomResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PublicRestroomPage parse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = root.path("response").isMissingNode() ? root : root.path("response");
            validateSuccess(response);

            JsonNode body = response.path("body").isMissingNode() ? response : response.path("body");
            JsonNode items = body.path("items");
            if (items.isObject() && items.has("item")) {
                items = items.path("item");
            }
            if (items.isMissingNode()) {
                items = body.path("data");
            }

            List<PublicRestroomRecord> records = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    records.add(toRecord(item));
                }
            } else if (items.isObject()) {
                records.add(toRecord(items));
            }

            return new PublicRestroomPage(
                    List.copyOf(records),
                    intValue(body, "pageNo", "page", "pageNumber"),
                    intValue(body, "numOfRows", "pageSize"),
                    longValue(body, "totalCount", "totalCnt", "total")
            );
        } catch (JsonProcessingException exception) {
            throw new PublicDataApiException("공공데이터 API JSON 응답을 해석하지 못했습니다.", exception);
        }
    }

    private void validateSuccess(JsonNode response) {
        JsonNode header = response.path("header");
        if (header.isMissingNode()) {
            return;
        }

        String resultCode = text(header, "resultCode", "resultCd");
        if (!resultCode.isBlank() && !"00".equals(resultCode) && !"NORMAL_SERVICE".equals(resultCode)) {
            throw new PublicDataApiException("공공데이터 API 호출 실패: " + text(header, "resultMsg", "resultMessage"));
        }
    }

    private PublicRestroomRecord toRecord(JsonNode item) {
        return new PublicRestroomRecord(
                text(item, "MNG_NO", "mngNo"),
                text(item, "FCLTY_NM", "RSTRM_NM", "fcltyNm"),
                text(item, "SE_NM", "FCLTY_SE_NM", "RSTRM_SE_NM", "toiletType"),
                text(item, "RSTRM_PSN_SE_NM", "OWNERSHIP_TYPE", "ownershipType"),
                text(item, "LCTN_ROAD_NM_ADDR", "RDNMADR", "roadAddress"),
                text(item, "LCTN_LOTNO_ADDR", "LNMADR", "jibunAddress"),
                decimal(item, "LAT", "latitude"),
                decimal(item, "LOT", "LON", "longitude"),
                integer(item, "MALE_TOILT_CNT", "MALE_WTRCLS_CNT", "maleToiletCount"),
                integer(item, "MALE_URNL_CNT", "MALE_UIL_CNT", "maleUrinalCount"),
                integer(item, "MALE_FRDBL_TOILT_CNT", "MALE_DSPSN_WTRCLS_CNT", "maleDisabledToiletCount"),
                integer(item, "MALE_FRDBL_URNL_CNT", "MALE_DSPSN_UIL_CNT", "maleDisabledUrinalCount"),
                integer(item, "MALE_CHLD_TOILT_CNT", "MALE_CHILDRN_WTRCLS_CNT", "maleChildToiletCount"),
                integer(item, "MALE_CHLD_URNL_CNT", "MALE_CHILDRN_UIL_CNT", "maleChildUrinalCount"),
                integer(item, "FEMALE_TOILT_CNT", "FEMAIL_WTRCLS_CNT", "FEMALE_WTRCLS_CNT", "femaleToiletCount"),
                integer(item, "FEMALE_FRDBL_TOILT_CNT", "FEMAIL_DSPSN_WTRCLS_CNT", "FEMALE_DSPSN_WTRCLS_CNT", "femaleDisabledToiletCount"),
                integer(item, "FEMALE_CHLD_TOILT_CNT", "FEMAIL_CHILDRN_WTRCLS_CNT", "FEMALE_CHILDRN_WTRCLS_CNT", "femaleChildToiletCount"),
                text(item, "MNG_INST_NM", "MNG_AGNC_NM", "managementAgencyName"),
                text(item, "TELNO", "MNG_AGNC_TELNO", "managementAgencyPhone"),
                text(item, "OPN_HR", "OPN_TIME", "openTime"),
                text(item, "OPN_HR_DTL", "OPN_TIME_DTL", "openTimeDetail"),
                text(item, "INSTL_YM", "installationDate"),
                text(item, "EMRGNCBLL_INSTL_YN", "EMRG_BELL_YN", "hasEmergencyBell"),
                text(item, "EMRGNCBLL_INSTL_PLC", "EMRG_BELL_LOC", "emergencyBellLocation"),
                text(item, "RSTRM_ENTRAN_CCTV_INSTL_EN", "CCTV_YN", "hasCctv"),
                text(item, "DIAP_EXCHCON_EN", "DIAPER_CHANGING_TABLE_YN", "hasDiaperTable"),
                text(item, "DIAP_EXCHCON_PLC", "DIAPER_CHANGING_TABLE_LOC", "diaperTableLocation"),
                text(item, "DAT_CRTR_YMD", "dataBaseDate"),
                text(item, "DAT_UPDT_PNT", "dataUpdatedAt")
        );
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private Integer integer(JsonNode node, String... names) {
        String value = text(node, names);
        if (value.isBlank()) {
            return 0;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        String value = text(node, names);
        if (value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int intValue(JsonNode node, String... names) {
        String value = text(node, names);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private long longValue(JsonNode node, String... names) {
        String value = text(node, names);
        return value.isBlank() ? 0L : Long.parseLong(value);
    }
}
