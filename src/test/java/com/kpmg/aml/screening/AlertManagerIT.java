package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.AlertManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AlertManager}.
 *
 * Verifies that {@link AlertManager#loadData()} correctly reads
 * {@code unassigned} and {@code pending} alerts from the DB into the
 * in-memory cache, and that {@code isExist()} returns the right results.
 */
@Import(AlertManager.class)
class AlertManagerIT extends AbstractPostgresIntegrationTest {

    @Autowired AlertManager alertManager;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetMaps() {
        // Re-initialise in-memory maps before each test so tests are isolated.
        // (The Spring context is shared; maps are not rolled back by @Transactional.)
        alertManager.init();
    }

    // ── empty table ───────────────────────────────────────────────────────────

    @Test
    void loadData_emptyTable_isExistReturnsFalse() {
        alertManager.loadData();
        assertThat(alertManager.isExist("CL001", "POL001")).isFalse();
    }

    // ── unassigned alert ──────────────────────────────────────────────────────

    @Test
    void loadData_unassignedAlert_isFoundByCustomerId() {
        insertAlert("CL-UA", "POL-UA", "unassigned", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-UA", null)).isTrue();
    }

    @Test
    void loadData_unassignedAlert_isFoundByPolicyNo() {
        insertAlert("CL-UA2", "POL-UA2", "unassigned", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-UNKNOWN", "POL-UA2")).isTrue();
    }

    // ── pending alert ─────────────────────────────────────────────────────────

    @Test
    void loadData_pendingAlert_isFoundByCustomerId() {
        insertAlert("CL-PD", "POL-PD", "pending", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-PD", null)).isTrue();
    }

    // ── alerts excluded from cache ────────────────────────────────────────────

    @Test
    void loadData_approvedAlert_notReturnedByIsExist() {
        insertAlert("CL-AP", "POL-AP", "approved", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-AP", "POL-AP")).isFalse();
    }

    @Test
    void loadData_rejectedAlert_notReturnedByIsExist() {
        insertAlert("CL-RJ", "POL-RJ", "rejected", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-RJ", "POL-RJ")).isFalse();
    }

    @Test
    void loadData_deletedUnassignedAlert_notReturnedByIsExist() {
        insertAlert("CL-DEL", "POL-DEL", "unassigned", true);

        alertManager.loadData();

        assertThat(alertManager.isExist("CL-DEL", "POL-DEL")).isFalse();
    }

    // ── isExist miss ──────────────────────────────────────────────────────────

    @Test
    void isExist_unknownCustomerAndPolicy_returnsFalse() {
        insertAlert("CL-UA3", "POL-UA3", "unassigned", false);

        alertManager.loadData();

        assertThat(alertManager.isExist("NOBODY", "NO-POLICY")).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void insertAlert(String customerId, String policyNo, String status, boolean deleted) {
        jdbc.update("""
                INSERT INTO aml.sch_screening_cus (customer_id, policy_no, status, deleted)
                VALUES (?, ?, ?, ?)
                """, customerId, policyNo, status, deleted);
    }
}
