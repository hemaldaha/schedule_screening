/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.PipelineSignal;
import com.kpmg.aml.screening.engine.PipelineStageReporter;
import com.kpmg.aml.screening.engine.Stage;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.engine.solr.ScreeningMode;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.util.ConfigLoader;
import com.kpmg.aml.screening.util.ConfigLoader.Screening;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 *
 * @author user
 */
@Service
@Slf4j
public class ScreeningGovernor implements PipelineStageReporter {

    private final ConfigLoader configLoader;

    //-- Executor services Registry 
    private final Map<Stage, ExecutorService> stageExecutors = new ConcurrentHashMap<>();

    // == Stage signal tracker
    private final Map<Stage, PipelineSignal> stageSignals = new ConcurrentHashMap<>();

    // -- Concurrency control
    private Semaphore chunkSemaphore;

    // -- Buffer
    private ChunkBuffer chunkBuffer;
    private ScoringBuffer scoringBuffer;
    private AlertBuffer alertBuffer;

    // -- Lifecycle flag
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

    // -- Run completion hook — fired once at end of initiateShutdown (normal completion only)
    private volatile Runnable pipelineCompletionHook;

    public ScreeningGovernor(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void init() {

        Screening cfg = configLoader.getScreening();

        //-- initial setup
        chunkSemaphore = new Semaphore(cfg.getMaxConcurrentChunks());

        chunkBuffer = new ChunkBuffer(cfg.getChunkBufferCapacity(), this);
        scoringBuffer = new ScoringBuffer(cfg.getScoringBufferCapacity(), this);
        alertBuffer = new AlertBuffer(cfg.getAlertBufferCapacity(), this);

        // -- initialize all Stage signals to RUNNING 
        Arrays.stream(Stage.values())
                .forEach(s -> stageSignals.put(s, PipelineSignal.RUNNING));

        log.info("[Governor] Initialised — maxConcurrentChunks={} chunkBuffer={} scoringBuffer={} alertBuffer={}",
                cfg.getMaxConcurrentChunks(),
                cfg.getChunkBufferCapacity(),
                cfg.getScoringBufferCapacity(),
                cfg.getAlertBufferCapacity());

    }

    // == Executor registration
    public void register(Stage stage, ExecutorService executor) {
        stageExecutors.put(stage, executor);
        log.info("[Governor] Registered executor for stage={}", stage.getDescription());
    }

    @Override
    public void report(Stage stage, PipelineSignal signal) {
        stageSignals.put(stage, signal);
        log.info("[Governor] Stage={} Signal={}", stage.getDescription(), signal);

        if (signal == PipelineSignal.EXHAUSTED) {
            // All Solr screening is done (awaitAllChunksComplete was called before this report).
            // Send the poison pill to ScoringConsumer.
            log.info("[Governor] Oracle exhausted — sending poison pill to ScoringBuffer");
            scoringBuffer.signalDone();
        }

        if (stage == Stage.PYTHON_SCORING && signal == PipelineSignal.DONE) {
            // ScoringConsumer finished — send poison pill to AlertConsumer
            log.info("[Governor] Python scoring done — sending poison pill to AlertBuffer");
            try {
                alertBuffer.putPoisonPill();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.error("[Governor] Interrupted while sending poison pill to AlertBuffer");
            }
        }

        evaluateShutdown();
    }

    // -- access acquire / Release (SEMAPHORE)
    public void acquireChunkSlot() throws InterruptedException {
        chunkSemaphore.acquire();
    }

    public void releaseChunkSlot() {
        chunkSemaphore.release();
    }

    // -- Buffer access 
    public ChunkBuffer getChunkBuffer() {
        return chunkBuffer;
    }

    public ScoringBuffer getScoringBuffer() {
        return scoringBuffer;
    }

    public AlertBuffer getAlertBuffer() {
        return alertBuffer;
    }

    // -- current status (Stage Signal)
    public Map<Stage, PipelineSignal> getStageSignals() {
        return Collections.unmodifiableMap(stageSignals);
    }

    // -- Progress percentage
    public int getProgressPercentage() {
        long doneCount = stageSignals.values().stream()
                .filter(s -> s == PipelineSignal.DONE || s == PipelineSignal.EXHAUSTED)
                .count();
        return (int) ((doneCount * 100) / Stage.values().length);
    }

    public void submitForScoring(CustomerInfo customer, List<ScreeningCandidate> candidates, ScreeningMode mode, long scenarioId, String runId) throws InterruptedException {
        var payload = new ScoringBuffer.ScoringPayload(customer, candidates, mode, scenarioId, runId);
        scoringBuffer.put(payload);
    }

    public void submitForAlert(AlertBuffer.AlertPayload payload) throws InterruptedException {
        alertBuffer.put(payload);
    }

    public void registerCompletionHook(Runnable hook) {
        this.pipelineCompletionHook = hook;
    }

    // -- Shutting down Gracefully 
    private void evaluateShutdown() {

        // ERROR any stage - halt immediately, no waiting for drain 
        boolean anyError = stageSignals.values().stream()
                .anyMatch(s -> s == PipelineSignal.ERROR);
        if (anyError) {
            log.error("[Governor] Pipeline ERROR detected — initiating emergency shutdown");
            initiateShutdown();
            return;
        }

        boolean oracleExhausted = stageSignals.get(Stage.ORACLE_READER) == PipelineSignal.EXHAUSTED;

        boolean allDownstreamDone = stageSignals.entrySet().stream()
                .filter(e -> e.getKey() != Stage.ORACLE_READER)
                .allMatch(e -> e.getValue() == PipelineSignal.DONE);

        if (oracleExhausted && allDownstreamDone) {
            Thread.ofVirtual().start(this::initiateShutdown);
        }
    }

    private void initiateShutdown() {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            return; // -- in the Shutting down process 
        }

        log.info("[Governor] All stages complete — initiating ordered shutdown");

        // -- shutdown in stage order
        Arrays.stream(Stage.values())
                .sorted(Comparator.comparingInt(Stage::getOrder))
                .forEach(stage -> {
                    ExecutorService ex = stageExecutors.get(stage);
                    if (ex != null) {
                        ex.shutdown();
                        try {
                            if (ex.awaitTermination(30, TimeUnit.SECONDS)) {
                                log.info("[Governor] Clean shutdown for stage={}",
                                        stage.getDescription());
                            } else {

                                ex.shutdownNow();
                                log.warn("[Governor] Forced shutdown for stage={}",
                                        stage.getDescription());
                            }
                        } catch (InterruptedException ex1) {
                            log.error("[Governor] Unexpected error occurred in Shutting down", ex1);
                            ex.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        log.info("[Governor] Pipeline shutdown complete — progress={}%", getProgressPercentage());

        if (pipelineCompletionHook != null) {
            try {
                Thread.interrupted(); // clear interrupt flag so JDBC works on the hook thread
                pipelineCompletionHook.run();
            } catch (Exception e) {
                log.error("[Governor] Completion hook failed", e);
            }
        }
    }

    @PreDestroy
    public void onDestroy() {
        if (!shutdownInitiated.get()) {
            log.warn("[Governor] Spring context closing — forcing shutdown");
            initiateShutdown();
        }
    }
}
