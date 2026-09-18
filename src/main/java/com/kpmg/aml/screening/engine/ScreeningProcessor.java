package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.dto.constant.SchScreeningStatus;
import com.kpmg.aml.screening.engine.pipeline.ScreeningGovernor;
import com.kpmg.aml.screening.engine.solr.SanctionScreeningStrategy;
import com.kpmg.aml.screening.engine.solr.SanctionStrategyBuilder;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.entity.ScheduleRunLogEntity;
import jakarta.annotation.PostConstruct;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.kpmg.aml.screening.entity.dto.ActiveScreeningView;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.dto.SanctionTypeView;
import com.kpmg.aml.screening.entity.persistence.SanctionTypeRepository;
import com.kpmg.aml.screening.entity.persistence.ScreeningScheduleRepository;
import com.kpmg.aml.screening.util.ConfigLoader;

/**
 * Fixes applied:
 *  1. Scenario futures now dispatched from filteredQueryList (frequency-filtered),
 *     not the raw queryList — weekly/monthly schedules no longer run on wrong days.
 *  2. Stale RUNNING/START RunLog rows are resolved to ERROR on startup,
 *     so crashed runs are visible in the DB instead of stuck forever.
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
    private final JdbcTemplate postgresJdbcTemplate;
    private final ScheduleRunLog scheduleRunLog;
    private final ScheduleMailService mailService;

    public ScreeningProcessor(ScreeningScheduleRepository schScreeningRepo,
                              SanctionTypeRepository sanctionTypeRepo,
                              SanctionStrategyBuilder strategyBuilder,
                              AlertManager alertManager,
                              @Qualifier("oracleJdbcTemplate") JdbcTemplate oracleJdbcTemplate,
                              ScreeningGovernor governor,
                              ConfigLoader configLoader,
                              JdbcTemplate postgresJdbcTemplate,
                              ScheduleRunLog scheduleRunLog,
                              ScheduleMailService mailService) {
        this.schScreeningRepo   = schScreeningRepo;
        this.sanctionTypeRepo   = sanctionTypeRepo;
        this.strategyBuilder    = strategyBuilder;
        this.alertManager       = alertManager;
        this.oracleJdbcTemplate = oracleJdbcTemplate;
        this.governor           = governor;
        this.configLoader       = configLoader;
        this.postgresJdbcTemplate = postgresJdbcTemplate;
        this.scheduleRunLog     = scheduleRunLog;
        this.mailService        = mailService;
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

        // FIX: resolve any RunLog rows left as RUNNING or START from a previous crashed run
        resolveStaleRunLogs();
    }

    /**
     * Marks any RunLog rows stuck as START or RUNNING (from a previous crashed run) as ERROR.
     * Called once at startup before the first run begins.
     */
    private void resolveStaleRunLogs() {
        List<SchScreeningStatus> staleStatuses = List.of(SchScreeningStatus.START, SchScreeningStatus.RUNNING);
        for (SchScreeningStatus staleStatus : staleStatuses) {
            List<ScheduleRunLogEntity> stale = scheduleRunLog.findAllByStatus(staleStatus);
            if (!stale.isEmpty()) {
                log.warn("[ScreeningProcessor] Found {} stale RunLog rows with status={} — marking as ERROR",
                        stale.size(), staleStatus);
                stale.forEach(log_ -> {
                    log_.setErrorLog("Resolved on restart — previous run terminated unexpectedly with status=" + staleStatus);
                    scheduleRunLog.updateRunLog(log_, SchScreeningStatus.ERROR);
                });
            }
        }
    }

    public void process(String runType) {

        final Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());

        // 1. Load alerts cache
        CompletableFuture<Void> loadAlerts = CompletableFuture.runAsync(alertManager::loadData, executor);

        // 2. Load active schedules
        CompletableFuture<List<ActiveScreeningView>> loadQueries =
                CompletableFuture.supplyAsync(schScreeningRepo::findActiveSchedules, executor);

        // 3. Load sanction types
        CompletableFuture<List<SanctionTypeView>> loadSanctions =
                CompletableFuture.supplyAsync(sanctionTypeRepo::findActiveSanctionTypes, executor);

        List<ActiveScreeningView> queryList;
        List<SanctionTypeView> sanctionList;

        try {
            queryList    = loadQueries.get();
            sanctionList = loadSanctions.get();
        } catch (InterruptedException | ExecutionException ex) {
            log.error("[ScreeningProcessor] Failed during pre-load phase", ex);
            governor.report(Stage.ORACLE_READER, PipelineSignal.DONE);
            return;
        }

        // Filter by frequency (daily / weekly=Wednesday / monthly=15th)
        List<ActiveScreeningView> filteredQueryList = queryList.stream()
                .filter(schedule -> shouldRunToday(schedule, calendar))
                .toList();

        if (filteredQueryList.isEmpty()) {
            log.info("[ScreeningProcessor] No schedules qualify to run today — skipping pipeline.");
            governor.report(Stage.ORACLE_READER, PipelineSignal.DONE);
            return;
        }
        mailService.sendScheduleEmails("Screening job started " ,
                runType+" Screening AML job has started. " + filteredQueryList.size() + " scenario(s) will run today.");
        loadScoreThresholdFromDb();
        log.info("[ScreeningProcessor] {} of {} schedules qualify to run today.",
                filteredQueryList.size(), queryList.size());

        // 4. Build strategy map
        Map<Long, SanctionScreeningStrategy> strategyMap = strategyBuilder.build(sanctionList);
        log.info("[ScreeningProcessor] Strategy map built for {} scenarios", strategyMap.size());

        // FIX: dispatch from filteredQueryList — was incorrectly using queryList
        List<CompletableFuture<Void>> scenarioFutures = filteredQueryList.stream()
                .map(scenario -> CompletableFuture.runAsync(() -> {

                    ScheduleRunLogEntity runLog = scheduleRunLog.saveRunLog(
                            scenario.getSanctionId(), SchScreeningStatus.START);
                    log.info("[ScreeningProcessor] Run log created for sanctionId={} — runLogId={}",
                            scenario.getSanctionId(), runLog.getId());

                    try {
                        SanctionScreeningStrategy strategy = strategyMap.get(scenario.getSanctionId());

                        if (strategy == null) {
                            log.warn("[ScreeningProcessor] No strategy for sanctionId={} — skipping",
                                    scenario.getSanctionId());
                            runLog.setErrorLog("No strategy found for sanctionId= " + scenario.getSanctionId());
                            scheduleRunLog.updateRunLog(runLog, SchScreeningStatus.ERROR);
                            log.warn("[ScreeningProcessor] No strategy for sanctionId={} — skipping",
                                    scenario.getSanctionId());
                            return;
                        }

                        log.info("[ScreeningProcessor] Scenario started id={} name={} thread={}",
                                scenario.getSanctionId(),
                                scenario.getScenarioName(),
                                Thread.currentThread().getName());

                        scheduleRunLog.updateRunLog(runLog, SchScreeningStatus.RUNNING);
                        processInChunks(scenario.getQuery(), strategy, scenario.getSanctionId());
                        scheduleRunLog.updateRunLog(runLog, SchScreeningStatus.END);

                        log.info("[ScreeningProcessor] Scenario completed sanctionId={}", scenario.getSanctionId());

                    } catch (Exception e) {
                        String rootMsg = getRootCauseMessage(e);
                        String queryExcerpt = scenario.getQuery() != null
                                ? scenario.getQuery().replace("\n", " ").trim()
                                : "N/A";
                        if (queryExcerpt.length() > 300) {
                            queryExcerpt = queryExcerpt.substring(0, 300) + "...";
                        }
                        runLog.setErrorLog(" RootMessage-----> " + rootMsg + " QueryException-----> " + queryExcerpt);
                        scheduleRunLog.updateRunLog(runLog, SchScreeningStatus.ERROR);
                    }

                }, executor))
                .toList();

        CompletableFuture<Void> all =
                CompletableFuture.allOf(scenarioFutures.toArray(new CompletableFuture[0]));
        boolean completedSuccessfully = false;

        try {
            all.join(); // WAIT until all async tasks finish

            log.info("[ScreeningProcessor] All scenarios complete — Oracle exhausted");


            waitForAlertGenerationToFinish();

            log.info("[ScreeningProcessor] Calling aml.assign_alerts()");


            governor.report(Stage.ORACLE_READER, PipelineSignal.EXHAUSTED);
            completedSuccessfully = true;



        } catch (Exception ex) {
            log.error("[ScreeningProcessor] Scenario failure", ex);
        }finally {
            if (completedSuccessfully) {
                log.info("[ScreeningProcessor] Sending END notification email");

                String subject = "Screening job completed successfully";
                String body = "Screening AML job has finished successfully.";
                mailService.sendScheduleEmails(subject, body);

            } else {
                String subject = "Screening job completed with errors";
                String body = "Screening AML job has finished successfully.";
                mailService.sendScheduleEmails(subject, body);

            }
            postgresJdbcTemplate.execute("CALL aml.assign_alerts()");

        }
    }
    private void waitForAlertGenerationToFinish() {
        long timeoutMs = 2 * 60 * 1000L;
        long pollMs = 200L;
        long waited = 0L;

        while (governor.getStageSignals().get(Stage.ALERT_GENERATION) != PipelineSignal.DONE) {
            if (governor.getStageSignals().get(Stage.ALERT_GENERATION) == PipelineSignal.ERROR) {
                log.warn("[ScreeningProcessor] ALERT_GENERATION reported ERROR — proceeding to assign_alerts anyway");
                return;
            }
            if (waited >= timeoutMs) {
                log.warn("[ScreeningProcessor] Timed out waiting for ALERT_GENERATION to finish — proceeding to assign_alerts anyway");
                return;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[ScreeningProcessor] Interrupted while waiting for ALERT_GENERATION — proceeding to assign_alerts");
                return;
            }
            waited += pollMs;
        }

        log.info("[ScreeningProcessor] ALERT_GENERATION stage DONE — proceeding to assign_alerts (waited {}ms)", waited);
    }
    // NEW — called after start email, before scenarios dispatch
    private void loadScoreThresholdFromDb() {
        Double dbThreshold = postgresJdbcTemplate.queryForObject(
                "SELECT threshold FROM kyc.kyc_config WHERE is_active = true ORDER BY creation_timestamp DESC LIMIT 1",
                Double.class
        );
        if (dbThreshold != null && dbThreshold > 0) {
            configLoader.getScreening().setScoreThreshold(dbThreshold);
        }
    }

    private boolean shouldRunToday(ActiveScreeningView schedule, Calendar calendar) {
        String frequency = schedule.getFrequency();

        if (frequency == null) {
            log.warn("[ScreeningProcessor] Schedule id={} has null frequency — skipping.",
                    schedule.getSanctionId());
            return false;
        }

        switch (frequency.toLowerCase()) {
            case "daily":
                return true;

            case "weekly":
                boolean isWednesday = calendar.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY;
                if (!isWednesday) {
                    log.info("[ScreeningProcessor] Skipping weekly schedule id={} — today is not Wednesday.",
                            schedule.getSanctionId());
                }
                return isWednesday;

            case "monthly":
                boolean is15th = calendar.get(Calendar.DAY_OF_MONTH) == 15;
                if (!is15th) {
                    log.info("[ScreeningProcessor] Skipping monthly schedule id={} — today is not the 15th.",
                            schedule.getSanctionId());
                }
                return is15th;

            default:
                log.warn("[ScreeningProcessor] Unknown frequency '{}' for schedule id={} — skipping.",
                        frequency, schedule.getSanctionId());
                return false;
        }
    }

    private void processInChunks(String queryStr, SanctionScreeningStrategy strategy, Long scenarioId) {

        int batchSize = configLoader.getScreening().getBatchSize();
        int offset    = 0;
        boolean hasData = true;

        String mdfQueryStr = queryStr.toLowerCase().contains("order by")
                ? queryStr + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
                : queryStr + " ORDER BY client_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";

        while (hasData) {
            List<CustomerInfo> chunk = oracleJdbcTemplate.query(
                    mdfQueryStr,
                    new DataClassRowMapper<>(CustomerInfo.class),
                    offset,
                    batchSize
            );

            if (chunk.isEmpty()) {
                hasData = false;
                log.info("[ScreeningProcessor] Scenario={} exhausted at offset={}", scenarioId, offset);
            } else {
                try {
                    governor.acquireChunkSlot();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log.warn("[ScreeningProcessor] Scenario={} interrupted at offset={}", scenarioId, offset);
                    break;
                }

                final int currentOffset = offset;
                executor.submit(() -> {
                    try {
                        log.info("[ScreeningProcessor] chunk={} offset={} thread={}",
                                chunk.size(), currentOffset, Thread.currentThread().getName());

                        List<CustomerInfo> eligibleList = chunk.stream()
                                .filter(c -> !alertManager.isExist(c.clientId(), c.policyNo()))
                                .toList();

                        log.info("[ScreeningProcessor] eligible={} of chunk={}", eligibleList.size(), chunk.size());

                        for (CustomerInfo customer : eligibleList) {
                            List<ScreeningCandidate> candidates = strategy.execute(customer);
                            if (!candidates.isEmpty()) {
                                governor.submitForScoring(customer, candidates, scenarioId);
                            }
                        }

                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        log.warn("[ScreeningProcessor] Chunk interrupted scenario={}", scenarioId);
                    } finally {
                        governor.releaseChunkSlot();
                    }
                });

                offset += batchSize;
            }
        }
    }
    private String getRootCauseMessage(Throwable t) {
        Throwable cause = t;
        String msg = null;
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                msg = cause.getMessage();  // keep overwriting — deepest non-null message wins
            }
            cause = cause.getCause();
        }
        return msg != null ? msg : t.getClass().getSimpleName();
    }
}