package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.pipeline.AlertBuffer;
import com.kpmg.aml.screening.engine.pipeline.AlertBuffer.CandidateResult;
import com.kpmg.aml.screening.engine.pipeline.DeadLetterService;
import com.kpmg.aml.screening.engine.pipeline.ScreeningGovernor;
import com.kpmg.aml.screening.engine.pipeline.ScoringBuffer;
import com.kpmg.aml.screening.engine.pipeline.ScoringBuffer.ScoringPayload;
import com.kpmg.aml.screening.engine.scoring.MatchRateClient;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.engine.solr.ScreeningMode;
import com.kpmg.aml.screening.monitor.ScreeningMetrics;
import com.kpmg.aml.screening.util.ConfigLoader;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stage 3 stub — drains ScoringBuffer and logs Solr candidates with a simulated
 * random score. Does not call the Python scoring service and does not push to
 * AlertBuffer. Replace this class body when Stage 3 is implemented.
 */
@Service
@Slf4j
public class ScoringConsumer {

    private static final Logger matchLog = LoggerFactory.getLogger("MATCH_RESULTS");

    private final ScreeningGovernor governor;
    private final MatchRateClient matchRateClient;
    private final ConfigLoader configLoader;
    private ExecutorService executor;
    private final DeadLetterService deadLetterService;
    private final ScreeningMetrics metrics;

    public ScoringConsumer(ScreeningGovernor governor, MatchRateClient matchRateClient, ConfigLoader configLoader, DeadLetterService deadLetterService, ScreeningMetrics metrics) {
        this.governor = governor;
        this.matchRateClient = matchRateClient;
        this.configLoader = configLoader;
        this.deadLetterService = deadLetterService;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name("scoring-consumer-", 0)
                        .factory()
        );

        governor.register(Stage.PYTHON_SCORING, executor);
        log.info("[ScoringConsumer] Executor registered with Governor");

        executor.submit(this::drain);
    }

    private void drain() {
        ScoringBuffer buffer = governor.getScoringBuffer();
        log.info("[ScoringConsumer] Drain loop started on thread={}", Thread.currentThread().getName());

        try {
            while (true) {
                ScoringPayload payload = buffer.take();

                if (buffer.isPoisonPill(payload)) {
                    log.info("[ScoringConsumer] Poison pill received — signalling done");
                    governor.report(Stage.PYTHON_SCORING, PipelineSignal.DONE);
                    break;
                }

                processPayload(payload);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[ScoringConsumer] Drain loop interrupted");
        }

        log.info("[ScoringConsumer] Drain loop exited");
    }

    private void processPayload(ScoringPayload payload) {
        var customer = payload.customer();
        var candidates = payload.candidates();
        var scenarioId = payload.scenarioId();
        var runId = payload.runId();
        boolean anyAboveThreshold = false;

        // -- Build dataArr: entry per name variant across all candidates
        // -- key format: <docId><variantIndex> mapping back to candidates
        List<Map<String, Object>> dataArr = new ArrayList<>();

        for (ScreeningCandidate candidate : candidates) {
            List<String> variants = candidate.getNameVariants();
            for (int i = 0; i < variants.size(); i++) {
                dataArr.add(Map.of(
                        "key", candidate.getDocId() + "|" + i,
                        "value", variants.get(i)
                ));
            }
        }

        // -- invoke Python scoring service - Aynch future
        long scoringStart = System.currentTimeMillis();
        String screeningMode = payload.mode() != null ? payload.mode().name() : ScreeningMode.NORMAL.name();
        List<Map<String, Object>> results = matchRateClient
                .getMatchRates(customer.name(), dataArr, screeningMode)
                .join();
        metrics.recordScoringLatency(System.currentTimeMillis() - scoringStart);

        // -- validate for Error response 
        if (results.size() == 1 && results.get(0).containsKey("status")) {
            //  -- Process can no longer execute. 
            log.error("[ScoringConsumer] Scoring unavailable for clientId={} — halting pipeline", customer.clientId());
            governor.report(Stage.PYTHON_SCORING, PipelineSignal.ERROR);
        } else {

            // scoreMap : key --> match 
            Map<String, Double> scoreMap;
            scoreMap = new HashMap<>();

            for (Map<String, Object> result : results) {
                String key = (String) result.get("key");
                double score = ((Number) result.get("match")).doubleValue();
                scoreMap.put(key, score);
            }

            // CandidateResult list -- Candidates and highest score
            List<CandidateResult> candidateResults = new ArrayList<>();
            double highestScore = 0.0;

            for (ScreeningCandidate candidate : candidates) {
                List<String> variants = candidate.getNameVariants();
                List<Double> variantScores = new ArrayList<>();
                double bestScore = 0.0;

                for (int i = 0; i < variants.size(); i++) {
                    String key = candidate.getDocId() + "|" + i;
                    double score = scoreMap.getOrDefault(key, 0.0);
                    variantScores.add(score);
                    bestScore = Math.max(bestScore, score);
                }

                double threshold = configLoader.getScreening().getThresholdFor(candidate.getCoreName());
                int trigger = configLoader.getScreening().getHighEntityCountTrigger();
                double elevated = configLoader.getScreening().getHighEntityCountThreshold();
                double effectiveThreshold = patternBThreshold(threshold, variants.size(), trigger, elevated);
                if (effectiveThreshold > threshold)
                    log.debug("[ScoringConsumer] Pattern B — high variant count ({} > {}) for docId={} "
                            + "core={}: threshold {} → {}",
                            variants.size(), trigger, candidate.getDocId(),
                            candidate.getCoreName(), threshold, effectiveThreshold);
                boolean isSelected = bestScore >= effectiveThreshold;
                anyAboveThreshold = anyAboveThreshold || isSelected;

                highestScore = Math.max(highestScore, bestScore);

                candidateResults.add(new CandidateResult(
                        candidate.getDocId(),
                        candidate.getCoreName(),
                        candidate.getNameVariants(),
                        variantScores,
                        bestScore,
                        isSelected
                ));

            }

            // Pattern D — suppress LXNX candidates for short names that match too many distinct
            // LXNX entities. Names like "Raj Kumar" or "Suresh Kumar" appear in 7+ unrelated
            // LXNX sanctions entries; a ≤2-token name with that many matches is a common-name
            // false positive, not a genuine sanction hit.
            //
            // Three tiers of suppression (all require ≤2 name tokens):
            //   Tier 1 (hard cap):   lxnxAbove > lxnxCommonNameCap (default 4)
            //                        Catches names with 5+ LXNX matches regardless of score.
            //   Tier 2 (score-aware): lxnxAbove >= 2 AND maxLxnxScore < 97%
            //                        Catches 3-4 match clusters at 95-96% (e.g. MAHESH KUMARA,
            //                        PRADEEP KUMARA, DINESH KUMARA) that are near-match FPs on
            //                        the 7M+ corpus but would not reach 100% for a genuine hit.
            //   Tier 3 (doubled surname): nameTokens == 1 (Pattern F) AND lxnxAbove >= 1
            //                        Catches data-entry duplications like "DISSANAYAKA DISSANAYAKA"
            //                        collapsed to 1 effective token — a single common surname
            //                        matching any LXNX entry is a structural FP.
            int lxnxCap = configLoader.getScreening().getLxnxCommonNameCap();
            long lxnxAbove = candidateResults.stream()
                    .filter(cr -> "lxnx_entities_core".equals(cr.coreName()) && cr.aboveThreshold())
                    .count();
            double maxLxnxScore = candidateResults.stream()
                    .filter(cr -> "lxnx_entities_core".equals(cr.coreName()) && cr.aboveThreshold())
                    .mapToDouble(CandidateResult::bestScore)
                    .max().orElse(0.0);
            // Strip punctuation (e.g. leading/trailing quotes stored in source data like '"ALI AKBAR"')
            // before counting tokens so that punctuation does not inflate the token count.
            // Pattern F — doubled-surname detection (e.g. "DISSANAYAKA DISSANAYAKA"): data-entry
            // duplication where the same surname is stored in both name fields produces a 2-token
            // name that escapes RESTRICTED mode but is semantically a single-surname name.
            // Collapse to 1 token so Pattern D applies the same LXNX suppression logic.
            String nameForTokenCount = customer.name().replaceAll("[^\\w\\s]", "").trim();
            String[] rawTokens = nameForTokenCount.isEmpty() ? new String[0] : nameForTokenCount.split("\\s+");
            int nameTokens = (rawTokens.length == 2 && rawTokens[0].equalsIgnoreCase(rawTokens[1]))
                    ? 1 : rawTokens.length;
            boolean patternDFires = nameTokens <= 2
                    && (lxnxAbove > lxnxCap                          // Tier 1: hard cap (>4 matches any score)
                        || (lxnxAbove >= 2 && maxLxnxScore < 97.0)   // Tier 2: ≥2 matches at <97% (e.g. PRASAD KUMARA, PRADEEP KUMARA)
                        || (nameTokens == 1 && lxnxAbove >= 1));      // Tier 3: doubled surname → any LXNX match is a FP
            if (patternDFires) {
                log.info("[ScoringConsumer] Pattern D — suppressing {} LXNX matches (maxScore={}) for short common name clientId={} name={}",
                        lxnxAbove, String.format("%.0f", maxLxnxScore), customer.clientId(), customer.name());
                candidateResults = candidateResults.stream()
                        .map(cr -> "lxnx_entities_core".equals(cr.coreName())
                                ? new CandidateResult(cr.docId(), cr.coreName(), cr.nameVariants(), cr.variantScores(), cr.bestScore(), false)
                                : cr)
                        .collect(java.util.stream.Collectors.toList());
                anyAboveThreshold = candidateResults.stream().anyMatch(CandidateResult::aboveThreshold);
            }

            metrics.recordScored(1);
            if (anyAboveThreshold) {  // -- there is at least one selected
                
                metrics.recordAboveThreshold(1);
                // Log full evidence trail — all candidates, qualifying and non-qualifying
                logMatchResults(customer.clientId(), customer.name(), candidateResults, highestScore);
                
                AlertBuffer.AlertPayload alertPayload = new AlertBuffer.AlertPayload(customer, scenarioId, candidateResults, runId, highestScore);
                try {
                    governor.submitForAlert(alertPayload);
                    log.info("[ScoringConsumer] Alert queued clientId={} highestScore={}", customer.clientId(), String.format("%.2f", highestScore));
                } catch (InterruptedException ex) {
                    log.error("[ScoringConsumer] Interrupted pushing to AlertBuffer clientId={}", customer.clientId());

                    executor.submit(() -> deadLetterService.handleMissedAlert(alertPayload));
                }
            }
        }

    }

    /**
     * Pattern B threshold decision.
     *
     * <p>When a Solr candidate has more name variants than {@code trigger}, it is
     * likely a common/generic name in the corpus (e.g. "Kumar", "Mohamed") rather
     * than a specific sanctioned individual.  Structural partial-match scores are
     * suppressed by requiring {@code elevated} confidence before the candidate is
     * counted as a hit.
     *
     * <p>The elevated threshold is only applied when it is strictly greater than the
     * configured base threshold — this ensures Pattern B never lowers the bar.
     *
     * @param baseThreshold  per-core threshold from config (e.g. 83.0)
     * @param variantCount   number of name variants the candidate has
     * @param trigger        variant count above which Pattern B fires (e.g. 4)
     * @param elevated       elevated threshold to apply (e.g. 92.0)
     * @return the effective threshold to compare {@code bestScore} against
     */
    static double patternBThreshold(double baseThreshold, int variantCount,
                                    int trigger, double elevated) {
        if (variantCount > trigger && elevated > baseThreshold)
            return elevated;
        return baseThreshold;
    }

    private void logMatchResults(String clientId, String customerName, List<CandidateResult> candidates, double highestScore) {
        Map<String, String> coreShortNames = Map.of(
                "fiu_individual_core", "FIU-IND",
                "fiu_org_core", "FIU-ORG",
                "un_consolidated_core", "UN",
                "local_watch_core", "LOCAL"
        );

        matchLog.info("SCREENING: clientId={} name={}", clientId, customerName);
        matchLog.info("  >>> {} candidate(s) evaluated — highest score: {}",
                candidates.size(), String.format("%.2f", highestScore));

        for (CandidateResult cr : candidates) {
            String shortName = coreShortNames.getOrDefault(cr.coreName(), cr.coreName());
            matchLog.info("  [{}] docId={} bestScore={} {}",
                    shortName,
                    cr.docId(),
                    String.format("%.2f", cr.bestScore()),
                    cr.aboveThreshold() ? "*** ABOVE THRESHOLD ***" : "");

            List<String> variants = cr.nameVariants();
            List<Double> scores = cr.variantScores();
            for (int i = 0; i < variants.size(); i++) {
                matchLog.info("       - [{}] {}",
                        String.format("%.2f", scores.get(i)),
                        variants.get(i));
            }
        }

        matchLog.info("------------------------------------------------------");
    }
}
