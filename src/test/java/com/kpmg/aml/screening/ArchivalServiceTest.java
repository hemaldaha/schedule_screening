package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.ArchivalService;
import com.kpmg.aml.screening.util.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchivalServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock ConfigLoader configLoader;

    @InjectMocks ArchivalService archivalService;

    private ConfigLoader.Screening screeningConfig(int years) {
        ConfigLoader.Screening s = new ConfigLoader.Screening();
        s.setRetentionYears(years);
        return s;
    }

    @Test
    void archiveOldExceptions_passesDefaultRetentionYearsToJdbc() {
        when(configLoader.getScreening()).thenReturn(screeningConfig(7));
        when(jdbcTemplate.update(anyString(), anyInt())).thenReturn(0);

        archivalService.archiveOldExceptions();

        verify(jdbcTemplate).update(anyString(), eq(7));
    }

    @Test
    void archiveOldExceptions_passesCustomRetentionYearsToJdbc() {
        when(configLoader.getScreening()).thenReturn(screeningConfig(3));
        when(jdbcTemplate.update(anyString(), anyInt())).thenReturn(0);

        archivalService.archiveOldExceptions();

        verify(jdbcTemplate).update(anyString(), eq(3));
    }

    @Test
    void archiveOldExceptions_sqlContainsAtomicCteMovePattern() {
        when(configLoader.getScreening()).thenReturn(screeningConfig(7));
        when(jdbcTemplate.update(anyString(), anyInt())).thenReturn(4);

        archivalService.archiveOldExceptions();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), anyInt());
        String sql = sqlCaptor.getValue().toUpperCase().replaceAll("\\s+", " ");
        assertThat(sql).contains("DELETE FROM AML.SCREENING_EXCEPTIONS");
        assertThat(sql).contains("INSERT INTO AML.SCREENING_EXCEPTIONS_ARCHIVE");
    }
}
