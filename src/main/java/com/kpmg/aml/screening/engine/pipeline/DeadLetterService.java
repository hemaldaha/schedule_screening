/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.pipeline;

import com.kpmg.aml.screening.engine.pipeline.AlertBuffer.AlertPayload;
import com.kpmg.aml.screening.entity.AlertEntity;
import com.kpmg.aml.screening.entity.ScreeningScheduleEntity;
import com.kpmg.aml.screening.entity.persistence.AlertEntityRepository;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

/**
 *
 * @author user
 */
@Service
@Slf4j
@DependsOn("screeningGovernor")
public class DeadLetterService {

    private static final Logger missedAlertLog = LoggerFactory.getLogger("MISSED_ALERTS");
    private static final long RETRY_DELAY_MS = 2_000;
    private AlertBuffer alertBuffer;
    private final ScreeningGovernor governor;
    private final AlertConsumer alertConsumer;
    private final AlertEntityRepository alertRepository;

    public DeadLetterService(ScreeningGovernor governor, AlertConsumer alertConsumer, AlertEntityRepository alertRepository) {
        this.governor = governor;
        this.alertConsumer = alertConsumer;
        this.alertRepository = alertRepository;
    }

    @PostConstruct
    public void init() {
        alertBuffer = governor.getAlertBuffer();
    }

    public void handleMissedAlert(AlertPayload payload) {

        try {
            Thread.sleep(RETRY_DELAY_MS);
            alertBuffer.put(payload);
            log.info("[DeadLetterService] Retry succeeded for clientId={}", payload.customer().clientId());
            return;

        } catch (InterruptedException ex) {
            log.error("[DeadLetterService] Retry interrupted for clientId={}", payload.customer().clientId());
        } catch (Exception retryEx) {
            log.error("[DeadLetterService] Retry failed for clientId={} reason={}",
                    payload.customer().clientId(), retryEx.getMessage());
        }

        // -- Retry FAILED , Alternate path to persist 
        // -- 1. Add all Data to missed-alerts.log
        // -- 2. Persist in DB , from back door 
        Thread.ofVirtual().start(() -> writeMissedAlert(payload));

        Thread.ofVirtual().start(() -> {
            try {
                Map<Long, ScreeningScheduleEntity> scenarioCache = new HashMap<>();
                AlertEntity entity = alertConsumer.buildAlertEntity(payload, scenarioCache);
                alertRepository.save(entity);
                log.info("[DeadLetterService] Persisted missed alert for clientId={}",
                        payload.customer().clientId());
            } catch (Exception ex) {
                log.error("[DeadLetterService] Failed to persist missed alert for clientId={} reason={}",
                        payload.customer().clientId(), ex.getMessage(), ex);
            }

        });

    }

    private void writeMissedAlert(AlertPayload payload) {

        var customer = payload.customer();

        missedAlertLog.info("================================================");
        missedAlertLog.info("MISSED ALERT — clientId={} name={} scenarioId={}",
                customer.clientId(),
                customer.name(),
                payload.scenarioId());
        missedAlertLog.info("  highestScore={}",
                String.format("%.2f", payload.highestScore()));

        payload.candidates().forEach(cr -> {
            missedAlertLog.info("  [{}] docId={} bestScore={} {}",
                    cr.coreName(),
                    cr.docId(),
                    String.format("%.2f", cr.bestScore()),
                    cr.aboveThreshold() ? "ABOVE THRESHOLD" : "");
            for (int i = 0; i < cr.nameVariants().size(); i++) {
                missedAlertLog.info("       - [{}] {}",
                        String.format("%.2f", cr.variantScores().get(i)),
                        cr.nameVariants().get(i));
            }
        });

        missedAlertLog.info("================================================");

        log.error("[DeadLetterService] MISSED ALERT recorded — clientId={} "
                + "check missed-alerts.log for full detail",
                customer.clientId());
    }
}
