package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.ScoringConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link ScoringConsumer#patternBThreshold}.
 *
 * Pattern B: when a Solr candidate has more than {@code trigger} name variants,
 * the effective threshold is raised to {@code elevated} (92%) to suppress
 * structural false positives caused by common/generic names in the LXNX corpus.
 *
 * Config in production: trigger=4, elevated=92.0, base=83.0 (lxnx: 88.0)
 */
class PatternBThresholdTest {

    private static final double BASE      = 83.0;
    private static final double ELEVATED  = 92.0;
    private static final int    TRIGGER   = 4;
    private static final double TOLERANCE = 0.001;

    // ── Pattern B does NOT fire ───────────────────────────────────────────────

    @Test
    void variantsAtTrigger_patternBDoesNotFire() {
        // Exactly trigger variants — condition is > not >=, so Pattern B is off
        double t = ScoringConsumer.patternBThreshold(BASE, TRIGGER, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(BASE, within(TOLERANCE));
    }

    @Test
    void variantsBelowTrigger_patternBDoesNotFire() {
        double t = ScoringConsumer.patternBThreshold(BASE, 2, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(BASE, within(TOLERANCE));
    }

    @Test
    void zeroVariants_patternBDoesNotFire() {
        double t = ScoringConsumer.patternBThreshold(BASE, 0, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(BASE, within(TOLERANCE));
    }

    // ── Pattern B fires ───────────────────────────────────────────────────────

    @Test
    void variantsAboveTrigger_patternBElevatesThreshold() {
        // 5 variants > trigger(4) → elevated threshold applied
        double t = ScoringConsumer.patternBThreshold(BASE, 5, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(ELEVATED, within(TOLERANCE));
    }

    @Test
    void highVariantCount_patternBElevatesThreshold() {
        // SURENDRA case: 43 LXNX variants
        double t = ScoringConsumer.patternBThreshold(BASE, 43, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(ELEVATED, within(TOLERANCE));
    }

    @Test
    void lxnxBaseThreshold_patternBElevatesCorrectly() {
        // LXNX base is 88% — elevated (92%) is still higher, so Pattern B fires
        double lxnxBase = 88.0;
        double t = ScoringConsumer.patternBThreshold(lxnxBase, 5, TRIGGER, ELEVATED);
        assertThat(t).isCloseTo(ELEVATED, within(TOLERANCE));
    }

    // ── Pattern B never lowers the threshold ─────────────────────────────────

    @Test
    void elevatedBelowBase_patternBDoesNotLowerThreshold() {
        // Misconfiguration guard: if elevated ≤ base, keep the base threshold
        double t = ScoringConsumer.patternBThreshold(BASE, 10, TRIGGER, 80.0);
        assertThat(t).isCloseTo(BASE, within(TOLERANCE));
    }

    @Test
    void elevatedEqualToBase_patternBDoesNotChangeThreshold() {
        double t = ScoringConsumer.patternBThreshold(BASE, 10, TRIGGER, BASE);
        assertThat(t).isCloseTo(BASE, within(TOLERANCE));
    }

    // ── Scoring outcome given Pattern B threshold ─────────────────────────────

    @ParameterizedTest(name = "variants={0} score={1} expected={2}")
    @CsvSource({
        // variants, bestScore, expectAboveThreshold
        "3,  85.0, true",   // few variants, score > 83% → admitted
        "3,  82.9, false",  // few variants, score < 83% → rejected
        "5,  88.0, false",  // many variants, score < 92% → suppressed by Pattern B
        "5,  91.9, false",  // many variants, score just below 92% → suppressed
        "5,  92.0, true",   // many variants, score exactly 92% → admitted
        "5,  95.0, true",   // many variants, score > 92% → admitted
        "43, 100.0, true",  // SURENDRA-style: 43 variants at 100% → still admitted
        "43, 85.0, false",  // SURENDRA-style: 85% score → suppressed by Pattern B
    })
    void scoringOutcome(int variantCount, double bestScore, boolean expectAbove) {
        double threshold = ScoringConsumer.patternBThreshold(BASE, variantCount, TRIGGER, ELEVATED);
        assertThat(bestScore >= threshold).isEqualTo(expectAbove);
    }
}
