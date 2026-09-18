package com.kpmg.aml.screening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity for aml.screening_runs.
 *
 * One row per batch screening session. Inserted at run start with status=RUNNING
 * and updated at completion with final counts and status=COMPLETED or FAILED.
 *
 * run_id is generated in ScreeningProcessor as yyyyMMdd_HHmmss — no @GeneratedValue.
 */
@Getter
@Setter
@Entity
@Table(name = "screening_runs", schema = "aml")
public class ScreeningRunEntity {

    // ── Identity ──────────────────────────────────────────────────────────────

    @Id
    @Column(name = "run_id", nullable = false, length = 20)
    private String runId;

    // ── Timing ────────────────────────────────────────────────────────────────

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;              // NULL while RUNNING

    @Column(name = "duration_seconds")
    private Integer durationSeconds;                // computed at completion

    // ── Status ────────────────────────────────────────────────────────────────

    @Column(name = "status", nullable = false, length = 20)
    private String status;                          // RUNNING / COMPLETED / FAILED

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;                    // set if status = FAILED

    // ── Run context ───────────────────────────────────────────────────────────

    @Column(name = "triggered_by", length = 20)
    private String triggeredBy;                     // SCHEDULED / MANUAL

    @Column(name = "screening_version", length = 50)
    private String screeningVersion;

    // ── Volume ────────────────────────────────────────────────────────────────

    @Column(name = "total_customers")
    private Integer totalCustomers;

    @Column(name = "exception_count")
    private Integer exceptionCount;

    @Column(name = "alert_count")
    private Integer alertCount;

    // ── Exception breakdown ───────────────────────────────────────────────────

    @Column(name = "skipped_count")
    private Integer skippedCount;

    @Column(name = "infra_failed_count")
    private Integer infraFailedCount;

    @Column(name = "transformed_count")
    private Integer transformedCount;

    @Column(name = "restricted_count")
    private Integer restrictedCount;

    @Column(name = "nic_only_count")
    private Integer nicOnlyCount;

    // ── Constructors ──────────────────────────────────────────────────────────

    public ScreeningRunEntity() {}
}
