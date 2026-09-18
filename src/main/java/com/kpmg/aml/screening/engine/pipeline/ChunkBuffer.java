/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.PipelineSignal;
import com.kpmg.aml.screening.engine.PipelineStageReporter;
import com.kpmg.aml.screening.engine.Stage;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author user
 */
@Slf4j
public class ChunkBuffer {

    private static final List<CustomerInfo> POISON_PILL = List.of();
    private final ArrayBlockingQueue<List<CustomerInfo>> queue;
    private final PipelineStageReporter reporter;

    ChunkBuffer(int capacity, PipelineStageReporter reporter) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.reporter = reporter;
    }

    void put(List<CustomerInfo> chunk) throws InterruptedException {
        queue.put(chunk);
    }

    List<CustomerInfo> take() throws InterruptedException {
        return queue.take();
    }

    boolean isPoisonPill(List<CustomerInfo> chunk) {
        return chunk == POISON_PILL;
    }

    void signalExhausted() {
        try {
            queue.put(POISON_PILL);
            reporter.report(Stage.ORACLE_READER, PipelineSignal.EXHAUSTED);
            log.info("[ChunkBuffer] Oracle exhausted signal sent");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[ChunkBuffer] Interrupted while signalling exhausted");
        }
    }

    int size() {
        return queue.size();
    }
}
