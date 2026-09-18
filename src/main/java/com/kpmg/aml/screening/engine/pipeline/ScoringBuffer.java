/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.PipelineSignal;
import com.kpmg.aml.screening.engine.PipelineStageReporter;
import com.kpmg.aml.screening.engine.Stage;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author user
 */
@Slf4j
public class ScoringBuffer {

    public record ScoringPayload(CustomerInfo customer, List<ScreeningCandidate> candidates, long scenarioId) {

    }

    private static final ScoringPayload POISON_PILL = new ScoringPayload(null, null, 0);
    private final ArrayBlockingQueue<ScoringPayload> queue;
    private final PipelineStageReporter reporter;

    ScoringBuffer(int capacity, PipelineStageReporter reporter) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.reporter = reporter;
    }

    void put(ScoringPayload payload) throws InterruptedException {
        queue.put(payload);
    }

    public ScoringPayload take() throws InterruptedException {
        return queue.take();
    }

    public boolean isPoisonPill(ScoringPayload payload) {
        return payload == POISON_PILL;
    }

    void signalDraining() {
        reporter.report(Stage.SOLR_SCREENING, PipelineSignal.DRAINING);
        log.info("[ScoringBuffer] Solr screening draining signal sent");
    }

    public void signalDone() {

        try {
            queue.put(POISON_PILL);
            reporter.report(Stage.SOLR_SCREENING, PipelineSignal.DONE);
            log.info("[ScoringBuffer] Solr screening done signal sent");
        } catch (InterruptedException ex) {
            log.warn("[ScoringBuffer] Interrupted while signalling done");
        }
    }

}
