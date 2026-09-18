/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for aml.screening_exceptions.
 *
 * @author user
 */
public interface ScreeningExceptionRepository extends JpaRepository<ScreeningExceptionEntity, String> {

    List<ScreeningExceptionEntity> findByRunId(String runId);

    List<ScreeningExceptionEntity> findByClientId(String clientId);

    List<ScreeningExceptionEntity> findByAction(String action);

    // ── Drill-down: all exceptions for a run (paginated) ──────────────────
    Page<ScreeningExceptionEntity> findByRunId(String runId, Pageable pageable);

    /** Filter by action within a run — SKIPPED, SCREENED_RESTRICTED, TRANSFORMED_AND_SCREENED, SCREENED. */
    Page<ScreeningExceptionEntity> findByRunIdAndAction(String runId, String action, Pageable pageable);

    /** Filter by screening mode within a run — e.g. NIC_ONLY. */
    Page<ScreeningExceptionEntity> findByRunIdAndScreeningMode(String runId, String screeningMode, Pageable pageable);

    /** Exceptions where at least one sanction list returned CANDIDATE_FOUND within a run. */
    @Query("SELECT e FROM ScreeningExceptionEntity e WHERE e.runId = :runId AND " +
           "(e.fiuInd = 'CANDIDATE_FOUND' OR e.fiuOrg = 'CANDIDATE_FOUND' OR e.unConsolidated = 'CANDIDATE_FOUND' " +
           "OR e.localWatch = 'CANDIDATE_FOUND' OR e.lexisNexis = 'CANDIDATE_FOUND' OR e.dowJones = 'CANDIDATE_FOUND')")
    Page<ScreeningExceptionEntity> findHitsByRunId(@Param("runId") String runId, Pageable pageable);

    /** Exceptions with an infrastructure failure (failureReason IS NOT NULL) within a run. */
    Page<ScreeningExceptionEntity> findByRunIdAndFailureReasonIsNotNull(String runId, Pageable pageable);

    /**
     * Date-range query used by GET /api/screening/exceptions.
     * {@code start} = startDate.atStartOfDay(), {@code end} = endDate.atTime(LocalTime.MAX).
     * Sort and page size are controlled by the {@link Pageable} argument supplied by
     * the controller (default: createdAt DESC, 20 per page).
     */
    Page<ScreeningExceptionEntity> findByCreatedAtBetween(
            LocalDateTime start, LocalDateTime end, Pageable pageable);

    /**
     * Single aggregation query for run completion counts.
     * Returns Object[] with 4 values: [totalCount, skippedCount, restrictedCount, transformedCount].
     * SUM columns return null when no matching rows exist — callers must null-check.
     */
    @Query("SELECT COUNT(e), " +
           "SUM(CASE WHEN e.action = 'SKIPPED'                  THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.action = 'SCREENED_RESTRICTED'       THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.action = 'TRANSFORMED_AND_SCREENED'  THEN 1 ELSE 0 END) " +
           "FROM ScreeningExceptionEntity e WHERE e.runId = :runId")
    List<Object[]> getCountsByRunId(@Param("runId") String runId);

    /** Customers with at least one sanction list returning CANDIDATE_FOUND. */
    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e WHERE e.runId = :runId AND " +
           "(e.fiuInd = 'CANDIDATE_FOUND' OR e.fiuOrg = 'CANDIDATE_FOUND' OR e.unConsolidated = 'CANDIDATE_FOUND' " +
           "OR e.localWatch = 'CANDIDATE_FOUND' OR e.lexisNexis = 'CANDIDATE_FOUND' OR e.dowJones = 'CANDIDATE_FOUND')")
    long countHitsByRunId(@Param("runId") String runId);

    /** Customers screened on NIC only (screening_mode = NIC_ONLY). */
    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e " +
           "WHERE e.runId = :runId AND e.screeningMode = 'NIC_ONLY'")
    long countNicOnlyByRunId(@Param("runId") String runId);

    /** Customers where at least one list query failed (SYSTEM_TIMEOUT or PROVIDER_ERROR). */
    @Query("SELECT COUNT(e) FROM ScreeningExceptionEntity e " +
           "WHERE e.runId = :runId AND e.failureReason IS NOT NULL")
    long countInfraFailedByRunId(@Param("runId") String runId);
}
