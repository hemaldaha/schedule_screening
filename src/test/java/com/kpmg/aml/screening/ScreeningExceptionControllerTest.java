package com.kpmg.aml.screening;

import com.kpmg.aml.screening.controller.ScreeningExceptionController;
import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.ScreeningRunEntity;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningRunRepository;
import com.kpmg.aml.screening.util.DataSourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    value = ScreeningExceptionController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = DataSourceConfig.class
    )
)
@ActiveProfiles("test")
class ScreeningExceptionControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean ScreeningExceptionRepository exceptionRepo;
    @MockitoBean ScreeningRunRepository        runRepo;

    // ── GET /api/screening/runs ────────────────────────────────────────────────

    @Test
    void getRuns_returnsOkWithRunList() throws Exception {
        ScreeningRunEntity run = buildRun("20260101_000000", "COMPLETED");
        when(runRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of(run));

        mvc.perform(get("/api/screening/runs"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].runId").value("20260101_000000"))
           .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void getRuns_emptyList_returnsOkWithEmptyArray() throws Exception {
        when(runRepo.findAllByOrderByStartedAtDesc()).thenReturn(List.of());

        mvc.perform(get("/api/screening/runs"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /api/screening/runs/{runId}/exceptions ─────────────────────────────

    @Test
    void getExceptionsByRun_defaultFilter_returnsOkPage() throws Exception {
        ScreeningExceptionEntity exc = buildException("exc-001", "RUN-001", "SKIPPED");
        when(exceptionRepo.findByRunId(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(exc)));

        mvc.perform(get("/api/screening/runs/RUN-001/exceptions"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value("exc-001"))
           .andExpect(jsonPath("$.content[0].action").value("SKIPPED"));
    }

    @Test
    void getExceptionsByRun_skippedFilter_delegatesToCorrectQuery() throws Exception {
        ScreeningExceptionEntity exc = buildException("exc-002", "RUN-001", "SKIPPED");
        when(exceptionRepo.findByRunIdAndAction(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(exc)));

        mvc.perform(get("/api/screening/runs/RUN-001/exceptions?filter=SKIPPED"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].action").value("SKIPPED"));
    }

    @Test
    void getExceptionsByRun_unknownFilter_returns400() throws Exception {
        mvc.perform(get("/api/screening/runs/RUN-001/exceptions?filter=INVALID"))
           .andExpect(status().isBadRequest());
    }

    // ── GET /api/screening/exceptions ─────────────────────────────────────────

    @Test
    void getByDateRange_validDates_returnsOkPage() throws Exception {
        ScreeningExceptionEntity exc = buildException("exc-003", "RUN-001", "SCREENED_RESTRICTED");
        when(exceptionRepo.findByCreatedAtBetween(any(LocalDateTime.class),
                                                   any(LocalDateTime.class),
                                                   any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(exc)));

        mvc.perform(get("/api/screening/exceptions?startDate=2026-01-01&endDate=2026-01-31"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[0].id").value("exc-003"));
    }

    @Test
    void getByDateRange_endBeforeStart_returns400() throws Exception {
        mvc.perform(get("/api/screening/exceptions?startDate=2026-01-31&endDate=2026-01-01"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void getByDateRange_sameDateStartAndEnd_returnsOk() throws Exception {
        when(exceptionRepo.findByCreatedAtBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/screening/exceptions?startDate=2026-06-01&endDate=2026-06-01"))
           .andExpect(status().isOk());
    }

    // ── GET /api/screening/exceptions/{id} ────────────────────────────────────

    @Test
    void getById_existingId_returnsOkWithDetail() throws Exception {
        ScreeningExceptionEntity exc = buildException("exc-123", "RUN-001", "SKIPPED");
        exc.setOriginalName("John Smith");
        exc.setReasonCode("NAME_BLANK");
        when(exceptionRepo.findById("exc-123")).thenReturn(Optional.of(exc));

        mvc.perform(get("/api/screening/exceptions/exc-123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value("exc-123"))
           .andExpect(jsonPath("$.clientId").value("CL001"))
           .andExpect(jsonPath("$.action").value("SKIPPED"));
    }

    @Test
    void getById_nonExistentId_returns404() throws Exception {
        when(exceptionRepo.findById("no-such-id")).thenReturn(Optional.empty());

        mvc.perform(get("/api/screening/exceptions/no-such-id"))
           .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ScreeningRunEntity buildRun(String runId, String status) {
        ScreeningRunEntity run = new ScreeningRunEntity();
        run.setRunId(runId);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return run;
    }

    private ScreeningExceptionEntity buildException(String id, String runId, String action) {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        exc.setId(id);
        exc.setRunId(runId);
        exc.setClientId("CL001");
        exc.setOriginalName("Test Name");
        exc.setAction(action);
        exc.setReasonCode("NAME_BLANK");
        exc.setEntityType("UNKNOWN");
        return exc;
    }
}
