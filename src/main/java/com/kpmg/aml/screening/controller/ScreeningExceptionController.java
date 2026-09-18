package com.kpmg.aml.screening.controller;

import com.kpmg.aml.screening.dto.DetailedExceptionDto;
import com.kpmg.aml.screening.dto.RunSummaryDto;
import com.kpmg.aml.screening.dto.SummaryExceptionDto;
import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningRunRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for screening runs and the {@code aml.screening_exceptions} audit table.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/screening/runs                           — all runs, most-recent first</li>
 *   <li>GET /api/screening/runs/{runId}/exceptions        — paginated exceptions for a run;
 *       optional {@code ?filter=ALL|SKIPPED|RESTRICTED|NIC_ONLY|HITS|FAILED}</li>
 *   <li>GET /api/screening/exceptions?startDate=&endDate= — ad-hoc date-range scan</li>
 *   <li>GET /api/screening/exceptions/{id}               — full detail; 404 if not found</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/screening")
public class ScreeningExceptionController {

    private final ScreeningExceptionRepository repository;
    private final ScreeningRunRepository runRepository;

    public ScreeningExceptionController(ScreeningExceptionRepository repository,
                                        ScreeningRunRepository runRepository) {
        this.repository    = repository;
        this.runRepository = runRepository;
    }

    /**
     * Returns all screening runs ordered most-recent first.
     * Counts are 0 for a RUNNING row (not yet populated).
     */
    @GetMapping("/runs")
    public ResponseEntity<List<RunSummaryDto>> getRuns() {
        List<RunSummaryDto> runs = runRepository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(RunSummaryDto::from)
                .toList();
        return ResponseEntity.ok(runs);
    }

    /**
     * Returns a paginated list of exceptions for the given run, optionally filtered.
     *
     * <p>{@code filter} defaults to {@code ALL}. Other values:
     * {@code SKIPPED}, {@code RESTRICTED}, {@code NIC_ONLY}, {@code HITS}, {@code FAILED}.
     * Spring binds the query-param string to {@link ExceptionFilter} automatically;
     * an unrecognised value returns 400.
     *
     * <p>Default page: 0, size: 20, sorted by {@code createdAt DESC}.
     * Override via {@code &page=1&size=50&sort=createdAt,asc}.
     */
    @GetMapping("/runs/{runId}/exceptions")
    public ResponseEntity<Page<SummaryExceptionDto>> getExceptionsByRun(
            @PathVariable String runId,
            @RequestParam(defaultValue = "ALL") ExceptionFilter filter,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        if (runId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Page<ScreeningExceptionEntity> raw = filter.execute(repository, runId, pageable);
        return ResponseEntity.ok(raw.map(SummaryExceptionDto::from));
    }

    /**
     * Ad-hoc date-range scan — returns exceptions created within the given dates (inclusive).
     *
     * <p>Both {@code startDate} and {@code endDate} are required in {@code yyyy-MM-dd} format.
     * Returns 400 if either is missing, unparseable, or {@code endDate} is before {@code startDate}.
     */
    @GetMapping("/exceptions")
    public ResponseEntity<Page<SummaryExceptionDto>> getByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        if (endDate.isBefore(startDate)) {
            return ResponseEntity.badRequest().build();
        }

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end   = endDate.atTime(LocalTime.MAX);  // 23:59:59.999999999 — inclusive

        return ResponseEntity.ok(
                repository.findByCreatedAtBetween(start, end, pageable)
                          .map(SummaryExceptionDto::from));
    }

    /**
     * Returns the full audit-trail detail for a single exception row.
     * Returns 404 if no row exists with the given id.
     */
    @GetMapping("/exceptions/{id}")
    public ResponseEntity<DetailedExceptionDto> getById(@PathVariable String id) {
        return repository.findById(id)
                .map(DetailedExceptionDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
