/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.runner;

import com.kpmg.aml.screening.engine.ScreeningProcessor;
import com.kpmg.aml.screening.entity.persistence.AlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
@Slf4j
@Profile("test")
public class ScreeningTrigger implements ApplicationRunner {

    @Lazy
    @Autowired
    private ScreeningProcessor processor;

    private final AlertRepository repo;

    public ScreeningTrigger(AlertRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {

        log.info("SYSTEM INFO:  App starting......");

        processor.process();
       
    }

}
