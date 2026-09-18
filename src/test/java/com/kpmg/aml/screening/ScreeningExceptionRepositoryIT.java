package com.kpmg.aml.screening;

import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ScreeningExceptionRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired ScreeningExceptionRepository repo;
    @Autowired JdbcTemplate                 jdbc;
    @Autowired TestEntityManager            em;

    private static final String RUN_A = "20260101_000000";
    private static final String RUN_B = "20260102_000000";
    private static final Pageable PAGE = PageRequest.of(0, 20);

    // ── Basic CRUD ─────────────────────────────────────────────────────────────

    @Test
    void saveAndFindById_roundTrips() {
        ScreeningExceptionEntity exc = skipped("CL001", RUN_A);
        repo.save(exc);
        // Flush to DB and clear the 1st-level cache so the next findById
        // executes a fresh SELECT and reads the DB-generated created_at DEFAULT.
        em.flush();
        em.clear();

        ScreeningExceptionEntity found = repo.findById(exc.getId()).orElseThrow();
        assertThat(found.getClientId()).isEqualTo("CL001");
        assertThat(found.getAction()).isEqualTo("SKIPPED");
        assertThat(found.getCreatedAt()).isNotNull();   // populated by DB DEFAULT
    }

    @Test
    void findById_nonExistent_returnsEmpty() {
        assertThat(repo.findById("no-such-id")).isEmpty();
    }

    // ── findByRunId ────────────────────────────────────────────────────────────

    @Test
    void findByRunId_returnsOnlyMatchingRun() {
        repo.save(skipped("CL001", RUN_A));
        repo.save(skipped("CL002", RUN_A));
        repo.save(skipped("CL003", RUN_B));

        List<ScreeningExceptionEntity> results = repo.findByRunId(RUN_A);
        assertThat(results).hasSize(2)
                           .allMatch(e -> e.getRunId().equals(RUN_A));
    }

    // ── findByRunId (paginated) ────────────────────────────────────────────────

    @Test
    void findByRunIdPaged_returnsPagedResults() {
        repo.save(skipped("CL001", RUN_A));
        repo.save(skipped("CL002", RUN_A));

        Page<ScreeningExceptionEntity> page = repo.findByRunId(RUN_A, PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }

    // ── findByRunIdAndAction ───────────────────────────────────────────────────

    @Test
    void findByRunIdAndAction_filtersCorrectly() {
        repo.save(skipped("CL001", RUN_A));
        repo.save(restricted("CL002", RUN_A));

        Page<ScreeningExceptionEntity> skipped = repo.findByRunIdAndAction(RUN_A, "SKIPPED", PAGE);
        assertThat(skipped.getTotalElements()).isEqualTo(1);
        assertThat(skipped.getContent().get(0).getClientId()).isEqualTo("CL001");
    }

    // ── findByRunIdAndScreeningMode ────────────────────────────────────────────

    @Test
    void findByRunIdAndScreeningMode_nicOnly_filtersCorrectly() {
        ScreeningExceptionEntity nicOnlyExc = restricted("CL-NIC", RUN_A);
        nicOnlyExc.setScreeningMode("NIC_ONLY");
        repo.save(nicOnlyExc);
        repo.save(restricted("CL-REST", RUN_A));  // mode=RESTRICTED

        Page<ScreeningExceptionEntity> page =
                repo.findByRunIdAndScreeningMode(RUN_A, "NIC_ONLY", PAGE);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getClientId()).isEqualTo("CL-NIC");
    }

    // ── findHitsByRunId ────────────────────────────────────────────────────────

    @Test
    void findHitsByRunId_returnsOnlyCandidateFound() {
        ScreeningExceptionEntity hit = restricted("CL-HIT", RUN_A);
        hit.setFiuInd("CANDIDATE_FOUND");
        repo.save(hit);
        repo.save(restricted("CL-MISS", RUN_A));  // all lists ATTEMPTED

        Page<ScreeningExceptionEntity> hits = repo.findHitsByRunId(RUN_A, PAGE);
        assertThat(hits.getTotalElements()).isEqualTo(1);
        assertThat(hits.getContent().get(0).getClientId()).isEqualTo("CL-HIT");
    }

    @Test
    void findHitsByRunId_candidateFoundOnAnyList_isReturned() {
        ScreeningExceptionEntity exc = restricted("CL-UN", RUN_A);
        exc.setUnConsolidated("CANDIDATE_FOUND");
        repo.save(exc);

        assertThat(repo.findHitsByRunId(RUN_A, PAGE).getTotalElements()).isEqualTo(1);
    }

    // ── findByRunIdAndFailureReasonIsNotNull ───────────────────────────────────

    @Test
    void findByRunIdAndFailureReasonIsNotNull_returnsFailedRows() {
        ScreeningExceptionEntity failed = restricted("CL-FAIL", RUN_A);
        failed.setFailureReason("SYSTEM_TIMEOUT");
        failed.setFiuInd("FAILED");
        repo.save(failed);
        repo.save(restricted("CL-OK", RUN_A));

        Page<ScreeningExceptionEntity> page =
                repo.findByRunIdAndFailureReasonIsNotNull(RUN_A, PAGE);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getFailureReason()).isEqualTo("SYSTEM_TIMEOUT");
    }

    // ── findByCreatedAtBetween ────────────────────────────────────────────────

    @Test
    void findByCreatedAtBetween_includesBothBoundaries() {
        // Insert via JDBC so we can control created_at
        insertWithCreatedAt("EXC-DATE", RUN_A, "2026-06-15 12:00:00");

        LocalDateTime start = LocalDateTime.of(2026, 6, 15, 0, 0);
        LocalDateTime end   = LocalDateTime.of(2026, 6, 15, 23, 59, 59);
        Page<ScreeningExceptionEntity> page = repo.findByCreatedAtBetween(start, end, PAGE);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findByCreatedAtBetween_rowOutsideRange_notReturned() {
        insertWithCreatedAt("EXC-OUT", RUN_A, "2026-01-01 00:00:00");

        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
        LocalDateTime end   = LocalDateTime.of(2026, 6, 30, 23, 59);
        assertThat(repo.findByCreatedAtBetween(start, end, PAGE).getTotalElements()).isEqualTo(0);
    }

    // ── getCountsByRunId ──────────────────────────────────────────────────────

    @Test
    void getCountsByRunId_returnsCorrectAggregates() {
        repo.save(skipped("CL-S1", RUN_A));
        repo.save(skipped("CL-S2", RUN_A));
        repo.save(restricted("CL-R1", RUN_A));
        ScreeningExceptionEntity transformed = restricted("CL-T1", RUN_A);
        transformed.setAction("TRANSFORMED_AND_SCREENED");
        repo.save(transformed);

        List<Object[]> rows = repo.getCountsByRunId(RUN_A);
        assertThat(rows).hasSize(1);
        Object[] counts = rows.get(0);
        assertThat(((Number) counts[0]).longValue()).isEqualTo(4);  // total
        assertThat(((Number) counts[1]).longValue()).isEqualTo(2);  // skipped
        assertThat(((Number) counts[2]).longValue()).isEqualTo(1);  // restricted
        assertThat(((Number) counts[3]).longValue()).isEqualTo(1);  // transformed
    }

    // ── countHitsByRunId ──────────────────────────────────────────────────────

    @Test
    void countHitsByRunId_countsOnlyCandidateFoundRows() {
        ScreeningExceptionEntity hit = restricted("CL-HIT2", RUN_A);
        hit.setLexisNexis("CANDIDATE_FOUND");
        repo.save(hit);
        repo.save(restricted("CL-NO-HIT", RUN_A));

        assertThat(repo.countHitsByRunId(RUN_A)).isEqualTo(1);
    }

    // ── countNicOnlyByRunId ───────────────────────────────────────────────────

    @Test
    void countNicOnlyByRunId_countsNicOnlyRows() {
        ScreeningExceptionEntity nic = restricted("CL-NIC2", RUN_A);
        nic.setScreeningMode("NIC_ONLY");
        repo.save(nic);
        repo.save(restricted("CL-REST2", RUN_A));

        assertThat(repo.countNicOnlyByRunId(RUN_A)).isEqualTo(1);
    }

    // ── countInfraFailedByRunId ───────────────────────────────────────────────

    @Test
    void countInfraFailedByRunId_countsFailedRows() {
        ScreeningExceptionEntity failed = restricted("CL-INFRA", RUN_A);
        failed.setFailureReason("PROVIDER_ERROR");
        repo.save(failed);
        repo.save(restricted("CL-FINE", RUN_A));

        assertThat(repo.countInfraFailedByRunId(RUN_A)).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ScreeningExceptionEntity skipped(String clientId, String runId) {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        exc.setId(UUID.randomUUID().toString());
        exc.setRunId(runId);
        exc.setClientId(clientId);
        exc.setOriginalName("Test " + clientId);
        exc.setAction("SKIPPED");
        exc.setReasonCode("NAME_BLANK");
        exc.setEntityType("UNKNOWN");
        return exc;
    }

    private ScreeningExceptionEntity restricted(String clientId, String runId) {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        exc.setId(UUID.randomUUID().toString());
        exc.setRunId(runId);
        exc.setClientId(clientId);
        exc.setOriginalName(clientId);
        exc.setAction("SCREENED_RESTRICTED");
        exc.setReasonCode("NAME_SINGLE_TOKEN");
        exc.setEntityType("UNKNOWN");
        exc.setScreeningMode("RESTRICTED");
        return exc;
    }

    private void insertWithCreatedAt(String id, String runId, String createdAt) {
        jdbc.update("""
                INSERT INTO aml.screening_exceptions
                  (id, run_id, client_id, original_name, action, reason_code, entity_type, created_at)
                VALUES (?, ?, 'CL-DATE', 'Date Test', 'SKIPPED', 'NAME_BLANK', 'UNKNOWN', ?::timestamp)
                """, id, runId, createdAt);
    }
}
