package com.example.toiletbatch.region;

import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KakaoRegionProviderTest {
    @TempDir Path directory;
    @Test void legalCodesAndThreeLevelHierarchyArePreserved() throws Exception {
        try (var journal = new RegionJournal(directory.resolve("region.jsonl"))) {
            var provider = new KakaoRegionProvider("test-not-used", journal, Clock.systemUTC(), 1, 200);
            var region = provider.parse("""
                    {"documents":[
                    {"region_type":"B","code":"4111710100","region_1depth_name":"경기도","region_2depth_name":"수원시 영통구","x":1,"y":2},
                    {"region_type":"H","code":"4111751000","region_1depth_name":"경기도","region_2depth_name":"수원시 영통구"}]}
                    """);
            assertEquals("41", region.sidoCode()); assertEquals("41117", region.sigunguCode());
            assertEquals("수원시", region.cityName()); assertEquals("영통구", region.districtName());
            assertEquals("4111710100", region.legalDongCode()); assertEquals("4111751000", region.administrativeDongCode());
        }
    }
    @Test void sejongDoesNotInventMunicipalityName() throws Exception {
        try (var journal = new RegionJournal(directory.resolve("region.jsonl"))) {
            var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 1, 200);
            var r = provider.parse("""
                    {"documents":[{"region_type":"B","code":"3611010100","region_1depth_name":"세종특별자치시","region_2depth_name":""}]}
                    """);
            assertEquals("36110", r.sigunguCode()); assertNull(r.sigunguName()); assertNull(r.cityName());
        }
    }
    @Test void missingLegalDocumentBadCodesAndDisagreeingCodeSystemsAreExplicitFailures() throws Exception {
        try (var journal = new RegionJournal(directory.resolve("region.jsonl"))) {
            var provider = new KakaoRegionProvider("test", journal, Clock.systemUTC(), 1, 200);
            for (String payload : new String[]{"{}", "{\"documents\":[]}", "bad-json", """
                    {"documents":[{"region_type":"B","code":"abc","region_1depth_name":"대전","region_2depth_name":"유성구"}]}
                    """, """
                    {"documents":[{"region_type":"B","code":"3020012200","region_1depth_name":"대전광역시","region_2depth_name":"유성구"},
                    {"region_type":"H","code":"1168051000"}]}
                    """}) assertThrows(RegionProvider.Failure.class, () -> provider.parse(payload));
        }
    }
}
