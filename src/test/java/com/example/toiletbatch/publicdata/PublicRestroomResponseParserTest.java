package com.example.toiletbatch.publicdata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PublicRestroomResponseParserTest {

    private final PublicRestroomResponseParser parser = new PublicRestroomResponseParser(new ObjectMapper());

    @Test
    void acceptsKoreanSuccessResultCodeReturnedByThePublicDataApi() {
        PublicRestroomPage page = parser.parse("""
                {
                  "response": {
                    "header": { "resultCode": "정상", "resultMsg": "정상" },
                    "body": {
                      "items": { "item": [] },
                      "pageNo": 1,
                      "numOfRows": 100,
                      "totalCount": 0
                    }
                  }
                }
                """);

        assertEquals(0, page.totalCount());
        assertEquals(0, page.records().size());
    }
}
