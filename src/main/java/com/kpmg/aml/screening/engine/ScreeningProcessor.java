/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.pipeline.ScreeningGovernor;
import com.kpmg.aml.screening.engine.solr.SanctionScreeningStrategy;
import com.kpmg.aml.screening.engine.solr.SanctionStrategyBuilder;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.engine.solr.ScreeningResult;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.kpmg.aml.screening.entity.dto.ActiveScreeningView;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.dto.SanctionTypeView;
import com.kpmg.aml.screening.entity.ScreeningRunEntity;
import com.kpmg.aml.screening.entity.persistence.AlertEntityRepository;
import com.kpmg.aml.screening.entity.persistence.SanctionTypeRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningRunRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningScheduleRepository;
import com.kpmg.aml.screening.monitor.ScreeningMetrics;
import java.time.Duration;
import com.kpmg.aml.screening.util.ConfigLoader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 *
 * @author user
 */
@Service
@Slf4j
public class ScreeningProcessor {

    private ExecutorService executor;

    private final ScreeningScheduleRepository schScreeningRepo;
    private final SanctionTypeRepository sanctionTypeRepo;
    private final JdbcTemplate oracleJdbcTemplate;
    private final SanctionStrategyBuilder strategyBuilder;
    private final AlertManager alertManager;
    private final ScreeningGovernor governor;
    private final ConfigLoader configLoader;
    private final ScreeningMetrics metrics;
    private final ScreeningRunRepository screeningRunRepository;
    private final ScreeningExceptionRepository exceptionRepository;
    private final AlertEntityRepository alertEntityRepository;

    // -- util members
    public ScreeningProcessor(ScreeningScheduleRepository schScreeningRepo,
            SanctionTypeRepository sanctionTypeRepo,
            SanctionStrategyBuilder strategyBuilder,
            AlertManager alertManager,
            @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
            ScreeningGovernor governor,
            ConfigLoader configLoader,
            ScreeningMetrics metrics,
            ScreeningRunRepository screeningRunRepository,
            ScreeningExceptionRepository exceptionRepository,
            AlertEntityRepository alertEntityRepository) {
        this.schScreeningRepo = schScreeningRepo;
        this.sanctionTypeRepo = sanctionTypeRepo;
        this.strategyBuilder = strategyBuilder;
        this.alertManager = alertManager;
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.governor = governor;
        this.configLoader = configLoader;
        this.metrics = metrics;
        this.screeningRunRepository = screeningRunRepository;
        this.exceptionRepository = exceptionRepository;
        this.alertEntityRepository = alertEntityRepository;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("oracle-reader-", 0)
                        .factory()
        );

        governor.register(Stage.ORACLE_READER, executor);
        log.info("[ScreeningProcessor] Executor registered with Governor");
    }

    public void process() {

        // 1. loading Alerts 
        CompletableFuture<Void> loadAlerts = CompletableFuture.runAsync(alertManager::loadData, executor);

        // 2. Loading active schedules 
        CompletableFuture<List<ActiveScreeningView>> loadQueries = CompletableFuture.supplyAsync(schScreeningRepo::findActiveSchedules, executor);

        // 3. Loading Sanction types
        CompletableFuture<List<SanctionTypeView>> loadSanctions = CompletableFuture.supplyAsync(sanctionTypeRepo::findActiveSanctionTypes, executor);

        List<ActiveScreeningView> queryList;
        List<SanctionTypeView> sanctionList;

        try {
            queryList = loadQueries.get();
            sanctionList = loadSanctions.get();
            loadAlerts.get();
        } catch (InterruptedException | ExecutionException ex) {
            log.error("[ScreeningProcessor] Failed during pre-load phase", ex);
            governor.report(Stage.ORACLE_READER, PipelineSignal.DONE);
            return;
        }

        // 4. Building Sanction STRATEGY
        LocalDateTime runStartedAt = LocalDateTime.now();
        String runId = runStartedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        strategyBuilder.setRunId(runId);
        Map<Long, SanctionScreeningStrategy> strategyMap = strategyBuilder.build(sanctionList);
        log.info("[ScreeningProcessor] Strategy map built for {} scenarios — runId={}",
                strategyMap.size(), runId);
        metrics.startRun(0);

        // -- Insert run record — status=RUNNING
        ScreeningRunEntity runRow = new ScreeningRunEntity();
        runRow.setRunId(runId);
        runRow.setStartedAt(runStartedAt);
        runRow.setStatus("RUNNING");
        screeningRunRepository.save(runRow);
        log.info("[ScreeningProcessor] Run record inserted — runId={}", runId);

        // -- Register completion hook so updateRunRecord fires after all stages finish
        AtomicReference<Throwable> scenarioError = new AtomicReference<>();
        governor.registerCompletionHook(() -> updateRunRecord(runId, runStartedAt, scenarioError.get()));

        // -- Dispatch scenario to a v-thread
        List<CompletableFuture<Void>> scenarioFutures = queryList.stream()
                .map(scenario -> {
                    return CompletableFuture.runAsync(() -> {

                        SanctionScreeningStrategy strategy
                                = strategyMap.get(scenario.getSanctionId());

                        if (strategy == null) {
                            log.warn("[ScreeningProcessor] No strategy for sanctionId={} — skipping",
                                    scenario.getSanctionId());
                            return;
                        }

                        log.info("[ScreeningProcessor] Scenario started id={} name={} thread={}",
                                scenario.getSanctionId(),
                                scenario.getScenarioName(),
                                Thread.currentThread().getName());

                        processInChunks(scenario.getQuery(), strategy, scenario.getSanctionId(), runId);

                    }, executor);
                })
                .toList();

        // -- wait for all to complete, --> signal EXHAUST
        CompletableFuture.allOf(scenarioFutures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.error("[ScreeningProcessor] Scenario failure", ex);
                        scenarioError.set(ex);
                    }
                    governor.report(Stage.ORACLE_READER, PipelineSignal.EXHAUSTED);
                    log.info("[ScreeningProcessor] All scenarios complete — Oracle exhausted");
                    metrics.endRun();
                });

    }

    void updateRunRecord(String runId, LocalDateTime runStartedAt, Throwable ex) {
        try {
            ScreeningRunEntity row = screeningRunRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException(
                            "screening_runs row missing for runId: " + runId));

            LocalDateTime completedAt = LocalDateTime.now();
            Object[] counts = exceptionRepository.getCountsByRunId(runId).get(0);

            row.setCompletedAt(completedAt);
            row.setDurationSeconds((int) Duration.between(runStartedAt, completedAt).getSeconds());
            row.setStatus(ex != null ? "FAILED" : "COMPLETED");
            row.setErrorMessage(ex != null ? ex.getMessage() : null);
            row.setTotalCustomers((int) metrics.getTotalEligible());
            row.setExceptionCount(((Number) counts[0]).intValue());
            row.setSkippedCount(counts[1]    != null ? ((Number) counts[1]).intValue() : 0);
            row.setRestrictedCount(counts[2] != null ? ((Number) counts[2]).intValue() : 0);
            row.setTransformedCount(counts[3] != null ? ((Number) counts[3]).intValue() : 0);
            long totalAlerts = alertEntityRepository.countByRunIdAndDeletedFalse(runId);
            row.setAlertCount((int) totalAlerts);
            row.setNicOnlyCount((int) exceptionRepository.countNicOnlyByRunId(runId));
            row.setInfraFailedCount((int) exceptionRepository.countInfraFailedByRunId(runId));
            screeningRunRepository.save(row);
            log.info("[ScreeningProcessor] Run record updated — runId={} status={}", runId, row.getStatus());
        } catch (Exception updateEx) {
            log.error("[ScreeningProcessor] Failed to update screening_runs for runId={}", runId, updateEx);
        }
    }

    /*
        Executing alert query with limit and offset.
        Procssing customer records in chunks

     */
    private void processInChunks(String queryStr, SanctionScreeningStrategy strategy, Long scenarioId, String runId) {

        int batchSize = configLoader.getScreening().getBatchSize();
        int offset = 0;
        boolean hasData = true;
        List<Future<?>> chunkFutures = new ArrayList<>();

        String mdfQueryStr = queryStr.toLowerCase().contains("order by")
                ? queryStr + " LIMIT ? OFFSET ?"
                : queryStr + " ORDER BY client_id LIMIT ? OFFSET ?";

        while (hasData) {
            List<CustomerInfo> chunk = oracleJdbcTemplate.query(
                    mdfQueryStr,
                    new DataClassRowMapper<>(CustomerInfo.class),
                    batchSize,
                    offset
            );

            if (chunk.isEmpty()) {
                hasData = false;
                log.info("[ScreeningProcessor] Scenario={} exhausted at offset={}",
                        scenarioId, offset);
            } else {

                try {
                    governor.acquireChunkSlot();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("[ScreeningProcessor] Scenario={} interrupted at offset={}",
                            scenarioId, offset);
                    break;
                }

                // -- Level 2:  running v-thread
                final int currentOffset = offset;
                chunkFutures.add(executor.submit(() -> {
                    try {
                        log.info("[ScreeningProcessor] chunk={} offset={} thread={}",
                                chunk.size(), currentOffset,
                                Thread.currentThread().getName());
                        metrics.recordRead(chunk.size());

                        // -- Filter already alerted
                        List<CustomerInfo> eligibleList = chunk.stream()
                                .filter(c -> !alertManager.isExist(c.clientId(), c.policyNo()))
                                .toList();

                        log.info("[ScreeningProcessor] eligible={} of chunk={}",
                                eligibleList.size(), chunk.size());
                        metrics.recordEligible(eligibleList.size());

                        // -- execute strategy, push findings to scoring buffer
                        for (CustomerInfo customer : eligibleList) {
                            ScreeningResult result = strategy.execute(customer);

//                            // ---- TEST HARNESS BEGIN - Remove before production ----
//                            log.debug("[ScreeningProcessor] clientId={} name={} candidates={}",
//                                    customer.clientId(), customer.name(), result.candidates().size());
//                            // ---- TEST HARNESS END ---------------------------------
                            metrics.recordScreened(1);
                            if (!result.candidates().isEmpty()) {
                                governor.submitForScoring(customer, result.candidates(), result.mode(), scenarioId, runId);

                                metrics.recordCandidates(result.candidates().size());
                            }
                        }

                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        log.warn("[ScreeningProcessor] Chunk interrupted scenario={}",
                                scenarioId);
                    } finally {
                        governor.releaseChunkSlot();
                    }

                }));

                offset += batchSize;
            }
        } // --  while (hasData)

        // Wait for all chunk threads to finish before returning.
        // Prevents the scenario future from completing (and firing EXHAUSTED) while
        // chunk threads are still running Solr queries and pushing to ScoringBuffer.
        for (Future<?> f : chunkFutures) {
            try {
                f.get();
            } catch (ExecutionException ex) {
                log.error("[ScreeningProcessor] Chunk task failed scenario={}", scenarioId, ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("[ScreeningProcessor] Interrupted waiting for chunks scenario={}", scenarioId);
                break;
            }
        }
    }

}
