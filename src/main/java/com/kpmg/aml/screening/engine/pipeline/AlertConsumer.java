/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.PipelineSignal;
import com.kpmg.aml.screening.engine.Stage;
import com.kpmg.aml.screening.engine.pipeline.AlertBuffer.AlertPayload;
import com.kpmg.aml.screening.engine.pipeline.AlertBuffer.CandidateResult;
import com.kpmg.aml.screening.entity.AlertEntity;
import com.kpmg.aml.screening.entity.AlertStatus;
import com.kpmg.aml.screening.entity.ScreeningMatchEntity;
import com.kpmg.aml.screening.entity.ScreeningMatchVariantEntity;
import com.kpmg.aml.screening.entity.ScreeningScheduleEntity;
import com.kpmg.aml.screening.entity.persistence.AlertEntityRepository;
import com.kpmg.aml.screening.entity.repo.ScreeningMatchRepository;
import com.kpmg.aml.screening.util.ConfigLoader;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 *
 * @author user
 */
@Service
@Slf4j
public class AlertConsumer {

    @PersistenceContext
    private EntityManager entityManager;

    private final ScreeningGovernor governor;
    private final AlertEntityRepository alertRepository;
    private final ConfigLoader configLoader;
    private final ScreeningMatchRepository matchRepository;

    private ExecutorService executor;

    public AlertConsumer(ScreeningGovernor governor,
            AlertEntityRepository alertRepository,
            ScreeningMatchRepository matchRepository,
            ConfigLoader configLoader) {
        this.governor = governor;
        this.alertRepository = alertRepository;
        this.matchRepository = matchRepository;
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("alert-consumer-", 0)
                        .factory()
        );

        governor.register(Stage.ALERT_GENERATION, executor);
        log.info("[AlertConsumer] Executor registered with Governor");
        executor.submit(this::drain);
    }

    private void drain() {
        AlertBuffer buffer = governor.getAlertBuffer();
        int batchSize = configLoader.getScreening().getAlertBatchSize();
        long timeoutMs = configLoader.getScreening().getAlertBatchTimeoutMs();
        List<AlertPayload> payloadBatch = new ArrayList<>();
        long lastFlush = System.currentTimeMillis();

        log.info("[AlertConsumer] Drain loop started on thread={}", Thread.currentThread().getName());

        while (true) {

            // -- Poll with timeout [time-based flush]
            AlertPayload payload = null;
            try {
                payload = buffer.poll(timeoutMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("[AlertConsumer] Poll interrupted — flushing {} remaining records. Reason: {}",
                        payloadBatch.size(), ex.getMessage());
                if (!payloadBatch.isEmpty()) {
                    persistBatch(payloadBatch);
                }
                break;
            }

            // -- Timeout expired - time-based Flush 
            if (payload == null) {
                if (!payloadBatch.isEmpty()) {
                    log.debug("[AlertConsumer] Timeout flush — {} records", payloadBatch.size());

                    //  -- passs a copy from the batch list, clears the exising 
                    persistBatch(new ArrayList<>(payloadBatch));
                    payloadBatch.clear();
                }
                continue;
            }

            // -- POISON Pill  [Final flush and exit]
            if (buffer.isPoisonPill(payload)) {
                if (!payloadBatch.isEmpty()) {
                    log.info("[AlertConsumer] Final flush — {} records", payloadBatch.size());
                    //  -- passs a copy from the batch list, clears the exising 
                    persistBatch(new ArrayList<>(payloadBatch));
                    payloadBatch.clear();
                }
                log.info("[AlertConsumer] Poison pill received — signalling done");
                buffer.signalDone();
                governor.report(Stage.ALERT_GENERATION, PipelineSignal.DONE); 
                break;
            }

            // -- regular path 
            payloadBatch.add(payload);

            // -- Size-based flush
            if (payloadBatch.size() >= batchSize) {
                log.debug("[AlertConsumer] Size flush — {} records", payloadBatch.size());

                persistBatch(new ArrayList<>(payloadBatch));
                payloadBatch.clear();
            }

        }
    }

    // ---- Persist Data in Batches ------------
    private void persistBatch(List<AlertPayload> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            Map<Long, ScreeningScheduleEntity> scenarioCache = new HashMap<>();
            List<AlertEntity> entities = batch.stream()
                    .map(p -> buildAlertEntity(p, scenarioCache))
                    .toList();

            List<AlertEntity> saved;
            saved = alertRepository.saveAll(entities);

            // -- Build and persiste Evidence trail
            List<ScreeningMatchEntity> allMatches;
            allMatches = new ArrayList<>();

            for (int i = 0; i < saved.size(); i++) {

                allMatches.addAll(buildMatchEvidence(saved.get(i), batch.get(i)));
            }
            matchRepository.saveAll(allMatches);

            log.info("[AlertConsumer] Persisted {} alert(s) with evidence", saved.size());

        } catch (Exception ex) {
            log.error("[AlertConsumer] Batch persist failed — {} records at risk. reason={}",
                    batch.size(), ex.getMessage(), ex);
            // TODO: forward to DeadLetterService
        }
    }

    AlertEntity buildAlertEntity(AlertPayload payload,
            Map<Long, ScreeningScheduleEntity> scenarioCache) {
        var customer = payload.customer();

        ScreeningScheduleEntity scenario = scenarioCache.computeIfAbsent(
                payload.scenarioId(),
                id -> entityManager.getReference(ScreeningScheduleEntity.class, id));

        long aboveThresholdCount = payload.candidates().stream()
                .filter(AlertBuffer.CandidateResult::aboveThreshold)
                .count();

        AlertEntity entity = new AlertEntity();
        entity.setCustomerId(customer.clientId());
        entity.setName(customer.name());
        entity.setNic(customer.nic());
        entity.setPassport(customer.passport());
        entity.setPolicyNo(customer.policyNo());
        entity.setProposalDate(customer.proposalDate());
        entity.setMatchCount((int) aboveThresholdCount);
        entity.setStatus(AlertStatus.pending);
        entity.setCreatedDate(LocalDate.now());
        entity.setCreationTimestamp(LocalDateTime.now());
        entity.setDeleted(false);
        entity.setScenario(scenario);


        return entity;
    }

    List<ScreeningMatchEntity> buildMatchEvidence(AlertEntity savedAlert, AlertPayload payload) {
        LocalDateTime screenedAt = LocalDateTime.now();
        List<ScreeningMatchEntity> matches = new ArrayList<>();

        for (CandidateResult cr : payload.candidates()) {
            ScreeningMatchEntity match = new ScreeningMatchEntity();
            match.setAlert(savedAlert);
            match.setDocId(cr.docId());
            match.setListSource(cr.coreName());
            match.setBestScore(cr.bestScore());
            match.setAboveThreshold(cr.aboveThreshold());
            match.setScreenedAt(screenedAt);
            match.setAlertBasedOn(cr.alertBasedOn());

            List<ScreeningMatchVariantEntity> variants = new ArrayList<>();
            List<String> names = cr.nameVariants();
            List<Double> scores = cr.variantScores();

            for (int i = 0; i < names.size(); i++) {
                ScreeningMatchVariantEntity variant = new ScreeningMatchVariantEntity();
                variant.setMatch(match);
                variant.setVariantName(names.get(i));
                variant.setVariantScore(scores.get(i));
                variants.add(variant);
            }

            match.setVariants(variants);
            matches.add(match);
        }

        return matches;
    }
}
