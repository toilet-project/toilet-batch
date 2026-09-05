package com.example.toiletbatch.region;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.toiletbatch.region.RegionModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionReplayJobTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T03:00:00Z"), ZoneOffset.UTC);
    @TempDir Path directory;
    private Source source(long id) { return new Source(id, "대전광역시 유성구 대학로", null, new BigDecimal("36.3"), new BigDecimal("127.3")); }
    private Result result(Source s) {
        return new Result(s, s.point(), new Region("대전광역시", "30", "유성구", "30200", null, null, "3020012200", null),
                Status.VERIFIED, "ADDRESS_CORROBORATED", Check.MATCH, Check.UNKNOWN, "NONE", clock.millis()-1000, VERSION);
    }
    private RegionRepository repository(Source... rows) throws Exception {
        var repo = mock(RegionRepository.class);
        when(repo.page(anyLong(), eq(100))).thenAnswer(call -> (long)call.getArgument(0) == 0 ? List.of(rows) : List.of());
        when(repo.lock()).thenReturn(() -> {});
        return repo;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Long> counts(Map<String, Object> report) { return (Map<String, Long>)report.get("counts"); }

    @Test void dryRunUsesOnlySourceReadsAndSkipsChangedMissingAndNewRows() throws Exception {
        Source one=source(1), changed=source(2).withPoint(new Point(BigDecimal.ONE, BigDecimal.TEN));
        Source missing=new Source(3, "대전", null, null, null);
        var repo=repository(one, changed, missing, source(4));
        var report=new RegionReplayJob(repo, Map.of(1L,result(one),2L,result(source(2)),9L,result(source(9))),clock).run(false,100);
        assertEquals(1L,counts(report).get("eligible")); assertEquals(1L,counts(report).get("staleOrChanged"));
        assertEquals(1L,counts(report).get("missingCoordinates")); assertEquals(1L,counts(report).get("noResult"));
        assertEquals(1L,counts(report).get("inputToiletMissing")); assertEquals(0,report.get("apiCalls"));
        verify(repo,never()).apply(any(),anyBoolean()); verify(repo,never()).lock(); verify(repo,never()).stored(anyLong());
    }
    @Test void replayPreservesSourceAndResumesUsingStoredTimestamp() throws Exception {
        Source one=source(1),two=source(2); var repo=repository(one,two);
        Result first=result(one), second=result(two);
        when(repo.stored(1)).thenReturn(first);
        when(repo.apply(second,false)).thenReturn(new RegionRepository.Applied(false,false,second));
        var report=new RegionReplayJob(repo,Map.of(1L,first,2L,second),clock).run(true,100);
        assertEquals(1L,counts(report).get("alreadyStored")); assertEquals(1L,counts(report).get("applied"));
        verify(repo,never()).apply(eq(first),anyBoolean()); verify(repo).apply(second,false);
    }
    @Test void concurrentChangeIsReportedAndNeverRetriedWithNewCoordinates() throws Exception {
        Source s=source(1);Result r=result(s);var repo=repository(s);
        when(repo.apply(r,false)).thenReturn(new RegionRepository.Applied(true,false,r));
        var report=new RegionReplayJob(repo,Map.of(1L,r),clock).run(true,100);
        assertEquals(1L,counts(report).get("concurrentChange"));assertEquals(0L,counts(report).get("applied"));
    }
    @Test void staleFailureAndInvalidVerifiedCoordinatesAreExcluded() throws Exception {
        Result base=result(source(1));
        Result failure=new Result(base.source(),base.evaluated(),null,Status.REVERSE_FAILED,"CODE_CONFLICT",Check.UNKNOWN,Check.UNKNOWN,"NONE",clock.millis()-86400001,VERSION);
        Result invalid=new Result(source(2),new Point(BigDecimal.ONE,BigDecimal.TEN),base.region(),Status.VERIFIED,base.reason(),base.roadCheck(),base.jibunCheck(),"NONE",base.checkedEpochMillis(),VERSION);
        var report=new RegionReplayJob(repository(source(1),source(2)),Map.of(1L,failure,2L,invalid),clock).run(false,100);
        assertEquals(1L,counts(report).get("staleOrChanged"));assertEquals(1L,counts(report).get("invalidResult"));
        assertEquals(0L,counts(report).get("eligible"));
    }
    @Test void reviewedManualResultIsRecordedButNotPromotedToVerified() throws Exception {
        Result base=result(source(1));
        Result manual=new Result(base.source(),base.evaluated(),base.region(),Status.MISMATCH,"RECHECK_MANUAL_REVIEW",Check.MISMATCH,Check.UNKNOWN,"NONE",base.checkedEpochMillis(),VERSION);
        var repo=repository(base.source());when(repo.apply(manual,false)).thenReturn(new RegionRepository.Applied(false,false,manual));
        var report=new RegionReplayJob(repo,Map.of(1L,manual),clock).run(true,1);
        assertEquals(1L,counts(report).get("MISMATCH")); assertFalse(counts(report).containsKey("VERIFIED"));
        assertEquals(false,report.get("scanComplete"));
    }
    @Test void readerChecksHashRejectsTruncationAndKeepsNewestTimestamp() throws Exception {
        Path input=directory.resolve("results.jsonl");var json=new ObjectMapper();Result older=result(source(1));
        Result newer=new Result(older.source(),older.evaluated(),older.region(),older.status(),older.reason(),older.roadCheck(),older.jibunCheck(),older.fallback(),older.checkedEpochMillis()+1,VERSION);
        Files.writeString(input,json.writeValueAsString(Map.of("kind","result","value",newer))+"\n"+json.writeValueAsString(Map.of("kind","result","value",older))+"\n");
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        assertEquals(newer,RegionReplayCli.read(input,sha).get(1L));
        assertThrows(IllegalArgumentException.class,()->RegionReplayCli.read(input,"0".repeat(64)));
        Files.writeString(input,"{\"kind\":\"result\",\"value\":");
        String badSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        assertThrows(Exception.class,()->RegionReplayCli.read(input,badSha));
        assertEquals("{\"kind\":\"result\",\"value\":",Files.readString(input));
    }
}
