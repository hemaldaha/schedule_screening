/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.PipelineSignal;
import com.kpmg.aml.screening.engine.PipelineStageReporter;
import com.kpmg.aml.screening.engine.Stage;
import com.kpmg.aml.screening.engine.solr.ScreeningMode;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author user
 */
@Slf4j
public class AlertBuffer {

    public record CandidateResult(
            String docId,
            String coreName,
            List<String> nameVariants,
            List<Double> variantScores,
            double bestScore,
            boolean aboveThreshold) {

    }

    public record AlertPayload(
            CustomerInfo customer,
            Long scenarioId,
            List<CandidateResult> candidates,
            String runId,
            double highestScore) {

    }

    private static final AlertPayload POISON_PILL = new AlertPayload(null, null, null, null, -1.0);
    private final ArrayBlockingQueue<AlertPayload> queue;
    private final PipelineStageReporter reporter;

    AlertBuffer(int capacity, PipelineStageReporter reporter) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.reporter = reporter;
    }

    void put(AlertPayload payload) throws InterruptedException {
        queue.put(payload);
    }

    void putPoisonPill() throws InterruptedException {
        queue.put(POISON_PILL);
        log.info("[AlertBuffer] Poison pill sent to AlertConsumer");
    }

    AlertPayload take() throws InterruptedException {
        return queue.take();
    }

    AlertPayload poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean isPoisonPill(AlertPayload payload) {
        return payload == POISON_PILL;
    }

    void signalDraining() {
        reporter.report(Stage.PYTHON_SCORING, PipelineSignal.DRAINING);
        log.info("[AlertBuffer] Python scoring draining signal sent");
    }

    void signalDone() {
        try {
            queue.put(POISON_PILL);
            reporter.report(Stage.PYTHON_SCORING, PipelineSignal.DONE);
            log.info("[AlertBuffer] Python scoring done signal sent");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[AlertBuffer] Interrupted while signalling done");
        }
    }

    int size() {
        return queue.size();
    }
}
