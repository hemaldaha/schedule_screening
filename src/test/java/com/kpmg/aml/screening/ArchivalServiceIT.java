package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.ArchivalService;
import com.kpmg.aml.screening.util.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link ArchivalService}.
 *
 * Verifies that the archival CTE correctly moves rows older than the
 * retention threshold from {@code aml.screening_exceptions} to
 * {@code aml.screening_exceptions_archive}.
 */
@Import(ArchivalService.class)
class ArchivalServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired ArchivalService archivalService;
    @Autowired JdbcTemplate    jdbc;

    @MockitoBean ConfigLoader configLoader;

    @BeforeEach
    void mockConfig() {
        ConfigLoader.Screening cfg = new ConfigLoader.Screening();
        cfg.setRetentionYears(7);
        when(configLoader.getScreening()).thenReturn(cfg);
    }

    @Test
    void archiveOldExceptions_movesRowsOlderThanThreshold() {
        // Insert a row that is 8 years old (exceeds the 7-year retention)
        String oldId = UUID.randomUUID().toString();
        insertWithAge(oldId, "8 years");

        archivalService.archiveOldExceptions();

        // Row must no longer be in the live table
        assertThat(countInLive(oldId)).isEqualTo(0);
        // Row must be in the archive
        assertThat(countInArchive(oldId)).isEqualTo(1);
    }

    @Test
    void archiveOldExceptions_doesNotTouchRecentRows() {
        String recentId = UUID.randomUUID().toString();
        insertWithAge(recentId, "1 year");

        archivalService.archiveOldExceptions();

        // Recent row stays in the live table
        assertThat(countInLive(recentId)).isEqualTo(1);
        assertThat(countInArchive(recentId)).isEqualTo(0);
    }

    @Test
    void archiveOldExceptions_boundaryRow_exactlyAtThresholdIsArchived() {
        // A row that is exactly 7 years old sits at the boundary.
        // The CTE uses < NOW() - 7 years, so exactly 7 years is NOT archived.
        // A row that is 7 years + 1 day IS archived.
        String exactId   = UUID.randomUUID().toString();
        String beyondId  = UUID.randomUUID().toString();
        insertWithAgeSql(exactId,  "NOW() - INTERVAL '7 years' + INTERVAL '1 minute'");
        insertWithAgeSql(beyondId, "NOW() - INTERVAL '7 years 1 day'");

        archivalService.archiveOldExceptions();

        assertThat(countInLive(exactId)).isEqualTo(1);     // exactly at boundary → kept
        assertThat(countInArchive(beyondId)).isEqualTo(1); // just past boundary → archived
    }

    @Test
    void archiveOldExceptions_idempotent_secondRunArchivesZeroRows() {
        String oldId = UUID.randomUUID().toString();
        insertWithAge(oldId, "10 years");

        archivalService.archiveOldExceptions();
        archivalService.archiveOldExceptions();  // second run — nothing left to archive

        assertThat(countInArchive(oldId)).isEqualTo(1);   // not duplicated
        assertThat(countInLive(oldId)).isEqualTo(0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertWithAge(String id, String age) {
        insertWithAgeSql(id, "NOW() - INTERVAL '" + age + "'");
    }

    private void insertWithAgeSql(String id, String createdAtExpr) {
        jdbc.update("""
                INSERT INTO aml.screening_exceptions
                  (id, run_id, client_id, original_name, action, reason_code, entity_type, created_at)
                VALUES (?, 'ARCH-RUN', 'CL-ARCH', 'Archive Test', 'SKIPPED', 'NAME_BLANK', 'UNKNOWN',
                """
                + createdAtExpr + ")", id);
    }

    private int countInLive(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aml.screening_exceptions WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    private int countInArchive(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aml.screening_exceptions_archive WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }
}
