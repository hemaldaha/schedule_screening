package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.AbstractPostgresIntegrationTest;
import com.kpmg.aml.screening.engine.pipeline.ScreeningGovernor;
import com.kpmg.aml.screening.engine.solr.SanctionStrategyBuilder;
import com.kpmg.aml.screening.entity.ScreeningRunEntity;
import com.kpmg.aml.screening.entity.persistence.ScreeningRunRepository;
import com.kpmg.aml.screening.monitor.ScreeningMetrics;
import com.kpmg.aml.screening.util.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration test for alert_count in screening_runs.
 *
 * <p>Verifies that {@link ScreeningProcessor#updateRunRecord} sets
 * {@code alert_count} to exactly the number of non-deleted rows in
 * {@code sch_screening_cus} for the given run:
 *
 * <pre>
 *   alert_count = COUNT(*) FROM aml.sch_screening_cus
 *                 WHERE run_id = ? AND deleted = false
 * </pre>
 *
 * <p>Seed layout for {@code RUN_ID}:
 * <pre>
 *   sch_screening_cus : 5 non-deleted alert rows  (run_id set, deleted=false)
 *                       1 deleted alert row        (run_id set, deleted=true — excluded)
 * </pre>
 *
 * <p>Expected: {@code alert_count = 5}
 */
@Import(ScreeningProcessor.class)
class ScreeningProcessorAlertCountIT extends AbstractPostgresIntegrationTest {

    @Autowired ScreeningProcessor     processor;
    @Autowired ScreeningRunRepository runRepository;
    @Autowired TestEntityManager      em;
    @Autowired JdbcTemplate           jdbc;

    @MockitoBean ScreeningGovernor       governor;
    @MockitoBean SanctionStrategyBuilder strategyBuilder;
    @MockitoBean AlertManager            alertManager;
    @MockitoBean ConfigLoader            configLoader;
    @MockitoBean ScreeningMetrics        metrics;

    private static final String RUN_ID = "20260713_120000";

    @Test
    void updateRunRecord_alertCountEqualsDirectSscCount() {

        // ── Arrange ──────────────────────────────────────────────────────────────
        jdbc.update("""
                INSERT INTO aml.screening_runs (run_id, started_at, status)
                VALUES (?, ?, 'RUNNING')
                """, RUN_ID, LocalDateTime.now().minusSeconds(60));

        // 5 non-deleted alerts — all should be counted regardless of screening mode.
        insertAlert("C-001", false);
        insertAlert("C-002", false);
        insertAlert("C-003", false);
        insertAlert("C-004", false);
        insertAlert("C-005", false);

        // 1 deleted alert — must NOT be counted.
        insertAlert("C-DEL", true);

        when(metrics.getTotalEligible()).thenReturn(6L);

        // ── Act ──────────────────────────────────────────────────────────────────
        processor.updateRunRecord(RUN_ID, LocalDateTime.now().minusSeconds(30), null);

        em.flush();
        em.clear();

        // ── Assert ───────────────────────────────────────────────────────────────
        ScreeningRunEntity run = runRepository.findById(RUN_ID).orElseThrow();

        long directCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aml.sch_screening_cus WHERE run_id = ? AND deleted = false",
                Long.class, RUN_ID);

        assertThat(run.getAlertCount())
                .as("alert_count must equal direct COUNT(*) from sch_screening_cus")
                .isEqualTo((int) directCount)
                .isEqualTo(5);
    }

    private void insertAlert(String customerId, boolean deleted) {
        jdbc.update("""
                INSERT INTO aml.sch_screening_cus (customer_id, run_id, status, deleted)
                VALUES (?, ?, 'unassigned', ?)
                """, customerId, RUN_ID, deleted);
    }
}
