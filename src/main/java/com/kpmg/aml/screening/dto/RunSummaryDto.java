package com.kpmg.aml.screening.dto;

import com.kpmg.aml.screening.entity.ScreeningRunEntity;
import java.time.LocalDateTime;

/**
 * DTO for GET /api/screening/exceptions/runs — one entry per batch screening session.
 */
public record RunSummaryDto(
        String        runId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String        status,
        int           totalCustomers,
        int           exceptionCount,
        int           alertCount,
        int           skippedCount,
        int           restrictedCount,
        int           transformedCount,
        int           nicOnlyCount,
        int           infraFailedCount
) {
    public static RunSummaryDto from(ScreeningRunEntity e) {
        return new RunSummaryDto(
                e.getRunId(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getStatus(),
                e.getTotalCustomers()  != null ? e.getTotalCustomers()  : 0,
                e.getExceptionCount()  != null ? e.getExceptionCount()  : 0,
                e.getAlertCount()      != null ? e.getAlertCount()      : 0,
                e.getSkippedCount()    != null ? e.getSkippedCount()    : 0,
                e.getRestrictedCount() != null ? e.getRestrictedCount() : 0,
                e.getTransformedCount()!= null ? e.getTransformedCount(): 0,
                e.getNicOnlyCount()    != null ? e.getNicOnlyCount()    : 0,
                e.getInfraFailedCount()!= null ? e.getInfraFailedCount(): 0
        );
    }
}
