/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.scoring;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author user
 */
@Slf4j
public class EndpointSelector {

    private List<String> endpoints;
    private HttpClient httpClient;
    private final ConcurrentHashMap<String, Boolean> healthMap = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    private static final long RETRY_WAIT_MS = 2_500;

    private static volatile EndpointSelector INSTANCE;

    private EndpointSelector() {
    }

    private static class EPSelectorLoader {

        private static final EndpointSelector THE_INSTANCE = new EndpointSelector();

    }

    public static EndpointSelector getInstance() {
        return EPSelectorLoader.THE_INSTANCE;
    }

    void init(List<String> endpoints, HttpClient httpClient) {
        this.endpoints = endpoints;
        this.httpClient = httpClient;
        endpoints.forEach(ep -> healthMap.put(ep, true));
    }

    /**
     *
     * @return the next healthy endpoint URL with round-robin selection If no
     * healthy endpoint is found, waits RETRY_WAIT_MS, re-ping all endpoints
     * once, then tries again If still none available, throws
     * EndpointUnavailableException
     */
    String nextHealthy() {
        int total = endpoints.size();
        int start = counter.getAndIncrement();
        int maxAttempts = 3;
        int attemptCount = 0;

        String selectedUrl = null;

        // --- First pass, find next healthy endpoint
        String endpoint;
        do {
            endpoint = null;
            for (int i = 0; i < total; i++) {
                int idx = Math.floorMod(start + i, total);
                endpoint = endpoints.get(idx);

                if (healthMap.getOrDefault(endpoint, false)) {
                    selectedUrl = endpoint;
                    break;
                }
            }

            if (selectedUrl == null) {

                // -- no healthy endpoints during this pass
                attemptCount++;
                log.warn("[EndpointSelector] No healthy endpoints — attempt {}/{} — waiting {}ms",
                        attemptCount, maxAttempts, RETRY_WAIT_MS);

                if (attemptCount >= maxAttempts) {
                    // TODO: Alert system admin — all match-rate endpoints unavailable
                    // Suggested: send email/SMS notification or trigger monitoring webhook
                    log.error("[EndpointSelector] CRITICAL — all match-rate endpoints unreachable"
                            + " after {} attempts. System admin intervention required.", maxAttempts);
                    break;
                }

                try {
                    Thread.sleep(RETRY_WAIT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

            }

        } while (selectedUrl == null);

        if (selectedUrl == null) {
            log.error("[EndpointSelector] All match-rate endpoints unavailable after {} attempts"
                    + " — pipeline must halt", maxAttempts);
            throw new EndpointUnavailableException(
                    "All match-rate endpoints unavailable after " + maxAttempts + " attempts");
        }

        return selectedUrl;
    }

    void markUnhealthy(String endpoint) {
        healthMap.put(endpoint, Boolean.FALSE);
        log.warn("[EndpointSelector] Marked unhealthy: {}", endpoint);
    }

    void markedHealthy(String endpoint) {
        healthMap.put(endpoint, Boolean.TRUE);
    }

    void resetHealth() {
        HttpRequest req;
        HttpResponse<String> resp;
        for (String endpoint : endpoints) {

            req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/health"))
                    .timeout(Duration.ofMillis(3000))
                    .GET()
                    .build();

            try {
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                boolean isHealthy = resp.statusCode() == 200;
                healthMap.put(endpoint, isHealthy);
                
                log.info("[EndpointSelector] Health check {}: {}", endpoint, isHealthy ? "UP" : "DOWN");
                
            } catch (IOException | InterruptedException ex) {
                healthMap.put(endpoint, false);
                log.warn("[EndpointSelector] Health check failed {}: {}",endpoint, ex.getMessage());
            }

        }
    }

    //  --- Exception (Visible Package only) ------------
    static class EndpointUnavailableException extends RuntimeException {

        EndpointUnavailableException(String message) {
            super(message);
        }

    }
}
