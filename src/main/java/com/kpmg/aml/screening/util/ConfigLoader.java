/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        private double scoreThreshold;              // global default — used when no per-core override exists
        private Map<String, Double> scoreThresholds = new HashMap<>(); // per-core overrides keyed by Solr core name
        private int maxConcurrentChunks;

        /**
         * Returns the score threshold for a specific Solr core.
         * If no per-core override is configured, falls back to {@code score-threshold}
         * (the global default). This allows tuning noisy corpora (e.g. lxnx) independently.
         */
        public double getThresholdFor(String coreName) {
            return scoreThresholds.getOrDefault(coreName, scoreThreshold);
        }
        private int alertBatchSize;
        private int alertBatchTimeoutMs;
        private long metricsIntervalMs;
        private int retentionYears = 7;
        private String archivalCron = "0 0 2 1 * *";
        // Pattern B — high entity count threshold escalation
        private int highEntityCountTrigger = 4;        // variant count above which elevated threshold applies
        private double highEntityCountThreshold = 92.0; // elevated threshold (same scale as scoreThreshold)
        // Pattern D — common-name LXNX suppression
        private int lxnxCommonNameCap = 4; // suppress LXNX if a short name (≤2 tokens) matches more than this many distinct LXNX entities above threshold
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
