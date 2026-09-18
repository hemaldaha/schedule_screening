/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.runner;

import com.kpmg.aml.screening.engine.ScheduleMailService;
import com.kpmg.aml.screening.engine.ScreeningProcessor;
import com.kpmg.aml.screening.entity.ScheduleRunLogEntity;
import com.kpmg.aml.screening.entity.persistence.AlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * @author user
 */
@Component
@Slf4j
public class ScreeningTrigger  {

    @Lazy
    @Autowired
    private ScreeningProcessor processor;

    @Autowired
    private ScheduleMailService emailService;

    private final AlertRepository repo;

    private final AtomicBoolean running = new AtomicBoolean(false);


    @Autowired
    public ScreeningTrigger(
            AlertRepository repo) {
        this.repo = repo;


    }



    @Scheduled(cron = "0 0 17 * * ?", zone = "IST")
    public void runScheduled() {
        if (!running.compareAndSet(false, true)) {
            log.warn("SYSTEM WARN: Previous screening run still in progress — skipping this trigger.");

            return;
        }
        try {
            log.info("SYSTEM INFO: Scheduled automatic screening triggered.");
            executeScreening(true);
        } finally {
        running.set(false);
    }

    }

    public void manualRun() {
        log.info("SYSTEM INFO: Manual screening run triggered.");
        if (!running.compareAndSet(false, true)) {
            log.warn("Screening already in progress — manual trigger ignored.");
            return;
        }
        try {
            executeScreening(false);
        } finally {
            running.set(false);
        }
    }

    private void executeScreening(boolean isAutomatic) {
        String runType = isAutomatic ? "AUTOMATIC" : "MANUAL";

        try {
            log.info("SYSTEM INFO: {} screening run starting...", runType);
            processor.process(runType);

        } catch (Exception e) {

            sendErrorNotification(runType, e.getMessage());
        }
    }

    private void sendErrorNotification(String runType, String errorMessage) {
        try {
            String subject = "Screening " + runType + " schedule ERROR";
            String body    = "Screening AML " + runType + " schedule encountered an error: " + errorMessage;
            emailService.sendScheduleEmails(subject, body);
        } catch (Exception e) {
            log.warn("Failed to send error notification email: {}", e.getMessage());
        }
    }
}
