package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.pipeline.AlertBuffer;
import com.kpmg.aml.screening.engine.pipeline.AlertBuffer.CandidateResult;
import com.kpmg.aml.screening.engine.pipeline.DeadLetterService;
import com.kpmg.aml.screening.engine.pipeline.ScreeningGovernor;
import com.kpmg.aml.screening.engine.pipeline.ScoringBuffer;
import com.kpmg.aml.screening.engine.pipeline.ScoringBuffer.ScoringPayload;
import com.kpmg.aml.screening.engine.scoring.MatchRateClient;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.util.ConfigLoader;
import com.kpmg.aml.screening.util.SriLankanNicUtil;
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
 * Fixes applied:
 *  NIC exact-match scoring added.
 *
 *  How it works:
 *  - Customer NIC is resolved into old/new variants via SriLankanNicUtil.
 *  - Each candidate's idDocuments (NICs, passports) are compared against
 *    customer NIC variants using exact string match.
 *  - If any NIC variant matches exactly, that candidate receives a fixed
 *    score of 100.0 — bypassing Python entirely for that candidate.
 *  - NIC-matched candidates are always above threshold regardless of name score.
 *  - Python name scoring still runs for all candidates as before;
 *    the final bestScore is max(nicScore, nameScore).
 */
@Service
@Slf4j
public class ScoringConsumer {

    // Fixed score assigned when customer NIC exactly matches a candidate's NIC
    private static final double NIC_EXACT_MATCH_SCORE = 100.0;

    private static final Logger matchLog = LoggerFactory.getLogger("MATCH_RESULTS");

    private final ScreeningGovernor governor;
    private final MatchRateClient matchRateClient;
    private final ConfigLoader configLoader;
    private ExecutorService executor;
    private final DeadLetterService deadLetterService;

    public ScoringConsumer(ScreeningGovernor governor,
                           MatchRateClient matchRateClient,
                           ConfigLoader configLoader,
                           DeadLetterService deadLetterService) {
        this.governor          = governor;
        this.matchRateClient   = matchRateClient;
        this.configLoader      = configLoader;
        this.deadLetterService = deadLetterService;
    }

    @PostConstruct
    public void init() {
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("scoring-consumer-", 0).factory()
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
                    buffer.signalDone();
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
        var customer   = payload.customer();
        var candidates = payload.candidates();
        var scenarioId = payload.scenarioId();
        double threshold = configLoader.getScreening().getScoreThreshold();

        // FIX: resolve customer NIC into both formats once — used for exact matching
        SriLankanNicUtil.NicPair customerNics = SriLankanNicUtil.resolve(customer.nic());
        List<String> customerNicVariants = buildNicVariantList(customerNics);

        // Build dataArr for Python: one entry per name variant per candidate
        List<Map<String, Object>> dataArr = new ArrayList<>();
        for (ScreeningCandidate candidate : candidates) {
            List<String> variants = candidate.getNameVariants();
            for (int i = 0; i < variants.size(); i++) {
                dataArr.add(Map.of(
                        "key",   candidate.getDocId() + "|" + i,
                        "value", variants.get(i)
                ));
            }
        }

        // Call Python scoring service for name similarity
        List<Map<String, Object>> results = matchRateClient
                .getMatchRates(customer.name(), dataArr)
                .join();

        if (results.size() == 1 && results.get(0).containsKey("status")) {
            log.error("[ScoringConsumer] Scoring unavailable for clientId={} — halting pipeline",
                    customer.clientId());
            governor.report(Stage.PYTHON_SCORING, PipelineSignal.ERROR);
            return;
        }

        // Build scoreMap from Python results
        Map<String, Double> scoreMap = new HashMap<>();
        for (Map<String, Object> result : results) {
            String key  = (String) result.get("key");
            double score = ((Number) result.get("match")).doubleValue();
            scoreMap.put(key, score);
        }

        List<CandidateResult> candidateResults = new ArrayList<>();
        double highestScore      = 0.0;
        boolean anyAboveThreshold = false;

        for (ScreeningCandidate candidate : candidates) {
            List<String> nameVariants  = candidate.getNameVariants();
            List<Double> variantScores = new ArrayList<>();
            double bestNameScore       = 0.0;

            // Collect Python name scores
            for (int i = 0; i < nameVariants.size(); i++) {
                String key   = candidate.getDocId() + "|" + i;
                double score = scoreMap.getOrDefault(key, 0.0);
                variantScores.add(score);
                bestNameScore = Math.max(bestNameScore, score);
            }

            // FIX: NIC exact-match check — overrides name score if customer NIC
            //      matches any ID document stored on the candidate
            double nicScore   = computeNicScore(candidate, customerNicVariants);
            double bestScore  = Math.max(bestNameScore, nicScore);

            if (nicScore >= NIC_EXACT_MATCH_SCORE) {
                log.info("[ScoringConsumer] NIC exact match — clientId={} docId={} coreName={}",
                        customer.clientId(), candidate.getDocId(), candidate.getCoreName());
            }

            boolean isSelected     = bestScore >= threshold;
            anyAboveThreshold      = anyAboveThreshold || isSelected;
            highestScore           = Math.max(highestScore, bestScore);

            // "NIC" when NIC exact match drove the result, "NAME" otherwise
            String alertBasedOn = (nicScore >= NIC_EXACT_MATCH_SCORE) ? "NIC" : "NAME";

            candidateResults.add(new CandidateResult(
                    candidate.getDocId(),
                    candidate.getCoreName(),
                    nameVariants,
                    variantScores,
                    bestScore,
                    isSelected,
                    alertBasedOn
            ));
        }

        if (anyAboveThreshold) {
            logMatchResults(customer.clientId(), customer.name(), candidateResults, highestScore);
            AlertBuffer.AlertPayload alertPayload =
                    new AlertBuffer.AlertPayload(customer, scenarioId, candidateResults, highestScore);
            try {
                governor.submitForAlert(alertPayload);
                log.info("[ScoringConsumer] Alert queued clientId={} highestScore={}",
                        customer.clientId(), String.format("%.2f", highestScore));
            } catch (InterruptedException ex) {
                log.error("[ScoringConsumer] Interrupted pushing to AlertBuffer clientId={}",
                        customer.clientId());
                executor.submit(() -> deadLetterService.handleMissedAlert(alertPayload));
            }
        }
    }

    /**
     * Returns 100.0 if any of the candidate's idDocuments exactly matches
     * any of the customer's NIC variants (case-insensitive). Returns 0.0 otherwise.
     *
     * Only runs for cores that carry NIC data (fiu_individual_core, local_watch_core).
     * fiu_org_core and un_consolidated_core return 0.0 immediately.
     */
    /**
     * Returns 100.0 only when a candidate idDocument is a valid NIC AND
     * its resolved form exactly matches one of the customer's resolved NIC variants.
     *
     * Both sides are run through SriLankanNicUtil.resolve() so that:
     *   - embedded spaces / case differences in Solr data are stripped
     *   - old-format "681202358V" and new-format "196812023580" are treated as the same person
     *
     * Passport numbers and reference numbers stored in idDocuments are NOT NICs —
     * resolve() will return NicPair(null,null) for them, so they are silently skipped.
     *
     * Only runs for cores that carry NIC data (fiu_individual_core, local_watch_core).
     */
    private double computeNicScore(ScreeningCandidate candidate, List<String> customerNicVariants) {
        String core = candidate.getCoreName();

        // Only individual-type cores carry NIC — skip org and UN
        if (!"fiu_individual_core".equals(core) && !"local_watch_core".equals(core)) {
            return 0.0;
        }

        if (customerNicVariants.isEmpty()) {
            return 0.0;
        }

        List<String> candidateIds = candidate.getIdDocuments();
        if (candidateIds == null || candidateIds.isEmpty()) {
            return 0.0;
        }

        for (String rawCandidateId : candidateIds) {
            if (rawCandidateId == null || rawCandidateId.isBlank()) continue;

            // Run the candidate ID through NicUtil — strips spaces, validates format,
            // and produces both old/new forms. Non-NIC values (passports, refs) resolve
            // to NicPair(null, null) and are skipped automatically.
            SriLankanNicUtil.NicPair candidateNics = SriLankanNicUtil.resolve(rawCandidateId);
            if (!candidateNics.isUsable()) continue;

            // Collect resolved candidate NIC variants
            List<String> resolvedCandidateNics = new ArrayList<>();
            if (candidateNics.oldNic() != null) resolvedCandidateNics.add(candidateNics.oldNic().toUpperCase());
            if (candidateNics.newNic() != null) resolvedCandidateNics.add(candidateNics.newNic().toUpperCase());

            // Exact match — both sides are now in clean resolved form
            for (String customerNic : customerNicVariants) {
                if (resolvedCandidateNics.contains(customerNic.toUpperCase())) {
                    log.info("[ScoringConsumer] NIC exact match confirmed — customerNic={} candidateRaw={}",
                            customerNic, rawCandidateId);
                    return NIC_EXACT_MATCH_SCORE;
                }
            }
        }

        return 0.0;
    }

    /**
     * Builds a non-null, de-duplicated list of NIC string variants for the customer.
     */
    private List<String> buildNicVariantList(SriLankanNicUtil.NicPair nics) {
        List<String> variants = new ArrayList<>();
        if (nics.oldNic() != null && !nics.oldNic().isBlank()) {
            variants.add(nics.oldNic().trim().toUpperCase());
        }
        if (nics.newNic() != null && !nics.newNic().isBlank()) {
            variants.add(nics.newNic().trim().toUpperCase());
        }
        return variants;
    }

    private void logMatchResults(String clientId, String customerName,
                                 List<CandidateResult> candidates, double highestScore) {
        Map<String, String> coreShortNames = Map.of(
                "fiu_individual_core", "FIU-IND",
                "fiu_org_core",        "FIU-ORG",
                "un_consolidated_core","UN",
                "local_watch_core",    "LOCAL"
        );

        matchLog.info("SCREENING: clientId={} name={}", clientId, customerName);
        matchLog.info("  >>> {} candidate(s) evaluated — highest score: {}",
                candidates.size(), String.format("%.2f", highestScore));

        for (CandidateResult cr : candidates) {
            String shortName = coreShortNames.getOrDefault(cr.coreName(), cr.coreName());
            matchLog.info("  [{}] docId={} bestScore={} {}",
                    shortName, cr.docId(),
                    String.format("%.2f", cr.bestScore()),
                    cr.aboveThreshold() ? "*** ABOVE THRESHOLD ***" : "");

            List<String> variants = cr.nameVariants();
            List<Double>  scores  = cr.variantScores();
            for (int i = 0; i < variants.size(); i++) {
                matchLog.info("       - [{}] {}",
                        String.format("%.2f", scores.get(i)), variants.get(i));
            }
        }

        matchLog.info("------------------------------------------------------");
    }
}