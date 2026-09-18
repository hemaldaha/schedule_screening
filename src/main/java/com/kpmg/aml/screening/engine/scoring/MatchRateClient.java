/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.scoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.aml.screening.util.ConfigLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
@Slf4j
public class MatchRateClient {

    private final ConfigLoader configLoader;

    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private EndpointSelector endpointSelector;
    private ScheduledExecutorService healthChecker;

    public MatchRateClient(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    @PostConstruct
    public void init() {
        ConfigLoader.MatchRateSettings cfg = configLoader.getMatchRate();

        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getConnectTimeout()))
                .build();

        this.endpointSelector = EndpointSelector.getInstance();
        endpointSelector.init(cfg.getEndpoints(), httpClient);

        healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "match-rate-health");
            t.setDaemon(true);
            return t;
        });

        healthChecker.scheduleWithFixedDelay(
                this::runHealthChecks,
                0,
                cfg.getHealthCheckInterval(),
                TimeUnit.MILLISECONDS);

        log.info("[MatchRateClient] Initialised with endpoints: {}", cfg.getEndpoints());
    }

    @PreDestroy
    public void destroy() {

        if (healthChecker != null) {
            healthChecker.shutdown();
        }
    }

    // --- Public API
    public CompletableFuture<List<Map<String, Object>>> getMatchRates(String keyword, List<Map<String, Object>> dataArr, String screeningMode) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "keyword",        keyword,
                    "dataArr",        dataArr,
                    "screening_mode", screeningMode));
        } catch (JsonProcessingException ex) {
            log.error("[MatchRateClient] Serialization failed for keyword={}", keyword);
            return CompletableFuture.completedFuture(
                    List.of(Map.of("status", 409, "message", "serialization failed")));
        }

        String endpoint;
        try {
            endpoint = endpointSelector.nextHealthy();
        } catch (EndpointSelector.EndpointUnavailableException ex) {
            log.error("[MatchRateClient] No healthy endpoints available — {}", ex.getMessage());
            return CompletableFuture.completedFuture(
                    List.of(Map.of("status", 409, "message", "no healthy endpoints")));
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/match-rate"))
                .timeout(Duration.ofMillis(configLoader.getMatchRate().getReadTimeout()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        final String selectedEndpoint = endpoint;

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        endpointSelector.markUnhealthy(selectedEndpoint);
                        log.warn("[MatchRateClient] HTTP {} from {}",
                                resp.statusCode(), selectedEndpoint);
                        return List.<Map<String, Object>>of(
                                Map.of("status", 409, "message",
                                        "HTTP " + resp.statusCode()));
                    }
                    try {
                        List<Map<String, Object>> result = objectMapper.readValue(
                                resp.body(), new TypeReference<>() {
                        });
                        return result;
                    } catch (Exception e) {
                        log.warn("[MatchRateClient] Deserialization failed: {}", e.getMessage());
                        return List.<Map<String, Object>>of(
                                Map.of("status", 409, "message", "deserialization failed"));
                    }
                })
                .exceptionally(ex -> {
                    endpointSelector.markUnhealthy(selectedEndpoint);
                    log.warn("[MatchRateClient] Request failed on {}: {}",
                            selectedEndpoint, ex.getMessage());
                    return List.of(Map.of("status", 409, "message", "request failed"));
                });
    }

    // --- Heatbeat check ---
    private void runHealthChecks() {
        endpointSelector.resetHealth();
    }
}
