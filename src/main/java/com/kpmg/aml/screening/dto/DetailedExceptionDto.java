package com.kpmg.aml.screening.dto;

import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * Full audit-trail view of a {@code screening_exceptions} row for compliance review.
 * Returned by {@code GET /api/screening/exceptions/{id}}.
 *
 * <p>{@code failureReason} and {@code failureDetails} are non-null only when at least
 * one per-list column equals {@code "FAILED"}.
 */
@Getter
public class DetailedExceptionDto {

    // ── Identity ──────────────────────────────────────────────────────────────
    private String        id;
    private String        runId;

    // ── Customer ──────────────────────────────────────────────────────────────
    private String        clientId;
    private String        originalName;
    private String        normalizedName;
    private String        idNumber;
    private String        entityType;
    private LocalDate     dateOfBirth;
    private String        nationality;

    // ── Classification ────────────────────────────────────────────────────────
    private String        action;
    private String        reasonCode;
    private String        note;

    // ── Per-list screening results ────────────────────────────────────────────
    private String        fiuInd;
    private String        fiuOrg;
    private String        unConsolidated;
    private String        localWatch;
    private String        lexisNexis;
    private String        dowJones;

    // ── Infrastructure failure context ────────────────────────────────────────
    // Non-null only when at least one list column = FAILED.
    private String        failureReason;
    private String        failureDetails;

    // ── Audit ─────────────────────────────────────────────────────────────────
    private LocalDateTime createdAt;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DetailedExceptionDto() {}

    // ── Static factory ────────────────────────────────────────────────────────

    public static DetailedExceptionDto from(ScreeningExceptionEntity e) {
        DetailedExceptionDto dto = new DetailedExceptionDto();
        dto.id              = e.getId();
        dto.runId           = e.getRunId();
        dto.clientId        = e.getClientId();
        dto.originalName    = e.getOriginalName();
        dto.normalizedName  = e.getNormalizedName();
        dto.idNumber        = e.getIdNumber();
        dto.entityType      = e.getEntityType();
        dto.dateOfBirth     = e.getDateOfBirth();
        dto.nationality     = e.getNationality();
        dto.action          = e.getAction();
        dto.reasonCode      = e.getReasonCode();
        dto.note            = e.getNote();
        dto.fiuInd          = e.getFiuInd();
        dto.fiuOrg          = e.getFiuOrg();
        dto.unConsolidated  = e.getUnConsolidated();
        dto.localWatch      = e.getLocalWatch();
        dto.lexisNexis      = e.getLexisNexis();
        dto.dowJones        = e.getDowJones();
        dto.failureReason   = e.getFailureReason();
        dto.failureDetails  = e.getFailureDetails();
        dto.createdAt       = e.getCreatedAt();
        return dto;
    }
}
