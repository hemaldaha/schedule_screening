package com.kpmg.aml.screening.engine.solr;

import java.util.List;

/**
 * Carries the Solr candidates from a completed per-customer screening run
 * together with the {@link ScreeningMode} that was used to build the queries.
 *
 * <p>Created at the end of {@code SanctionStrategyBuilder.composeStrategy()} after
 * all list queries have finished. Flows through {@code ScoringBuffer.ScoringPayload}
 * so that {@code ScoringConsumer} can pass the mode to the Python scoring service,
 * enabling the NIC_ONLY early-exit (Task 14).
 */
public record ScreeningResult(
        List<ScreeningCandidate> candidates,
        ScreeningMode mode
) {}
