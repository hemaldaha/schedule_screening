/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Scope;

/**
 *
 * @author user
 */
@ConfigurationProperties(prefix = "application.config")
//@Configuration
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
@Getter
@Setter
public class ConfigLoader {

    private SolrSettings solr = new SolrSettings();
    private Screening screening = new Screening();
    private MatchRateSettings matchRate = new MatchRateSettings();

    @Getter
    @Setter
    public static class Screening {

        private int chunkBufferCapacity;
        private int scoringBufferCapacity;
        private int alertBufferCapacity;
        private int batchSize;
        private double scoreThreshold;
        private int maxConcurrentChunks;
        private int alertBatchSize;
        private int alertBatchTimeoutMs;
    }

    @Getter
    @Setter
    public static class MatchRateSettings {

        private List<String> endpoints;
        private int connectTimeout;
        private int readTimeout;
        private long healthCheckInterval;
    }
}
