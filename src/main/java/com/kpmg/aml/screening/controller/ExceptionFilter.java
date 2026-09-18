package com.kpmg.aml.screening.controller;

import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Filter constants for GET /api/screening/runs/{runId}/exceptions.
 *
 * <p>Each constant holds the repository delegate it calls, so the controller
 * contains zero routing logic — just {@code filter.execute(repo, runId, pageable)}.
 */
public enum ExceptionFilter {

    ALL        ((repo, runId, p) -> repo.findByRunId(runId, p)),
    SKIPPED    ((repo, runId, p) -> repo.findByRunIdAndAction(runId, "SKIPPED", p)),
    RESTRICTED ((repo, runId, p) -> repo.findByRunIdAndScreeningMode(runId, "RESTRICTED", p)),
    NIC_ONLY   ((repo, runId, p) -> repo.findByRunIdAndScreeningMode(runId, "NIC_ONLY", p)),
    HITS       ((repo, runId, p) -> repo.findHitsByRunId(runId, p)),
    FAILED     ((repo, runId, p) -> repo.findByRunIdAndFailureReasonIsNotNull(runId, p));

    private final FilterFunction fn;

    ExceptionFilter(FilterFunction fn) {
        this.fn = fn;
    }

    public Page<ScreeningExceptionEntity> execute(
            ScreeningExceptionRepository repo, String runId, Pageable pageable) {
        return fn.apply(repo, runId, pageable);
    }

    @FunctionalInterface
    public interface FilterFunction {
        Page<ScreeningExceptionEntity> apply(
                ScreeningExceptionRepository repo, String runId, Pageable pageable);
    }
}
