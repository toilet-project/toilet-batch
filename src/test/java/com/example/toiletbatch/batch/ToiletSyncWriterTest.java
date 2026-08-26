package com.example.toiletbatch.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletbatch.publicdata.PublicRestroomRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ToiletSyncWriterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void updatesExistingRowsInsertsNewRowsAndSkipsRowsWithoutManagementNumber() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0, 1);

        ToiletSyncWriter writer = new ToiletSyncWriter(jdbcTemplate);
        RestroomSyncWriteResult result = writer.upsertPage(List.of(record("EXISTING"), record("NEW"), record("")));

        assertEquals(1, result.updatedRecords());
        assertEquals(1, result.insertedRecords());
        assertEquals(1, result.skippedRecords());
        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));
    }

    private PublicRestroomRecord record(String managementNumber) {
        return new PublicRestroomRecord(
                managementNumber, "테스트", "개방", "공중", "도로명", "지번", null, null,
                1, 2, 0, 0, 0, 0, 3, 0, 0,
                "기관", "042-000-0000", "상시", "", "202601", "Y", "입구", "Y", "Y", "벽면", "20260101", "20260101000000"
        );
    }
}
