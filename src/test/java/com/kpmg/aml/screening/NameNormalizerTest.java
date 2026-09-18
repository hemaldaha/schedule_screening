package com.kpmg.aml.screening;

import com.kpmg.aml.screening.util.NameNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.kpmg.aml.screening.util.NameNormalizer.*;
import static org.assertj.core.api.Assertions.assertThat;

class NameNormalizerTest {

    // ── Blank / null ──────────────────────────────────────────────────────────

    @Test
    void classifyNull_returnsBlank() {
        var r = classify(null);
        assertThat(r.reasonCode()).isEqualTo(CODE_BLANK);
        assertThat(r.action()).isEqualTo(Action.SKIP_PLACEHOLDER);
        assertThat(r.screenName()).isEqualTo("");
    }

    @Test
    void classifyEmptyString_returnsBlank() {
        var r = classify("");
        assertThat(r.reasonCode()).isEqualTo(CODE_BLANK);
        assertThat(r.action()).isEqualTo(Action.SKIP_PLACEHOLDER);
    }

    @Test
    void classifyWhitespace_returnsBlank() {
        var r = classify("   ");
        assertThat(r.reasonCode()).isEqualTo(CODE_BLANK);
        assertThat(r.action()).isEqualTo(Action.SKIP_PLACEHOLDER);
    }

    // ── Placeholder ───────────────────────────────────────────────────────────

    // Verifies the .toUpperCase() path — lowercase input must still match
    @ParameterizedTest
    @ValueSource(strings = {
        "NOT MENTIONED", "not mentioned",
        "NOT AVAILABLE", "NOT APPLICABLE",
        "UNKNOWN", "N/A", "NA", "NONE", "-", "."
    })
    void classifyPlaceholder_returnsPlaceholder(String input) {
        var r = classify(input);
        assertThat(r.reasonCode())
                .as("Input '%s' should be classified as placeholder", input)
                .isEqualTo(CODE_PLACEHOLDER);
        assertThat(r.action()).isEqualTo(Action.SKIP_PLACEHOLDER);
    }

    // ── Dot expansion ─────────────────────────────────────────────────────────

    @Test
    void classifyDotExpansion_returnsTransformedDots() {
        var r = classify("A.B.Wickramaratne");
        assertThat(r.reasonCode()).isEqualTo(CODE_TRANSFORMED_DOTS);
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        assertThat(r.screenName()).isEqualTo("A B Wickramaratne");
    }

    // ── CamelCase split ───────────────────────────────────────────────────────

    @Test
    void classifyCamelCase_returnsTransformedCamel() {
        var r = classify("KamalPerera");
        assertThat(r.reasonCode()).isEqualTo(CODE_TRANSFORMED_CAMEL);
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        assertThat(r.screenName()).contains("Kamal").contains("Perera");
    }

    @Test
    void classifyCamelCaseComplex_splitAtAllBoundaries() {
        // "RKWickramarathne" — leading initials then camelCase surname
        var r = classify("RKWickramarathne");
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        assertThat(r.screenName()).contains("Wickramarathne");
    }

    // ── Invalid chars stripped ────────────────────────────────────────────────

    @Test
    void classifyInvalidCharsStripped_returnsStripped() {
        var r = classify("John Smith(999)");
        assertThat(r.reasonCode()).isEqualTo(CODE_STRIPPED_INVALID);
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        assertThat(r.screenName()).isEqualTo("John Smith");
    }

    @Test
    void classifyInvalidCharsSingleToken_returnsSkipAnomaly() {
        // After stripping, fewer than 2 tokens remain
        var r = classify("A1");
        assertThat(r.reasonCode()).isEqualTo(CODE_INVALID_CHARS);
        assertThat(r.action()).isEqualTo(Action.SKIP_ANOMALY);
    }

    // ── Single token ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"Velupillai", "Rashidi", "SOMAPALA"})
    void classifySingleToken_returnsScreenRestricted(String input) {
        var r = classify(input);
        assertThat(r.reasonCode()).isEqualTo(CODE_SINGLE_TOKEN);
        assertThat(r.action()).isEqualTo(Action.SCREEN_RESTRICTED);
        assertThat(r.screenName()).isEqualTo(input);
    }

    // ── Short all-caps single token → NAME_ALL_INITIALS (Bug fix: PA, VV etc.) ──

    @ParameterizedTest
    @ValueSource(strings = {"PA", "VV", "NK", "AB"})
    void classifyShortAllCaps2Letter_returnsAllInitials(String input) {
        // 2-letter all-uppercase tokens are initials abbreviations, not real names.
        // Confirmed bug: "PA" (customer 111165) was generating 23 LXNX false matches.
        var r = classify(input);
        assertThat(r.reasonCode())
                .as("Input '%s' should be classified as NAME_ALL_INITIALS", input)
                .isEqualTo(CODE_ALL_INITIALS);
        assertThat(r.action()).isEqualTo(Action.SKIP_ANOMALY);
    }

    @Test
    void classifySingleLetter_returnsAllInitials() {
        // A single uppercase letter is clearly an initial.
        var r = classify("P");
        assertThat(r.reasonCode()).isEqualTo(CODE_ALL_INITIALS);
        assertThat(r.action()).isEqualTo(Action.SKIP_ANOMALY);
    }

    // ── Regression: 3-letter real names must NOT be reclassified ─────────────

    @ParameterizedTest
    @ValueSource(strings = {"LEE", "KIM", "IAN", "ANA"})
    void classifyThreeLetterName_remainsScreenRestricted(String input) {
        // 3-char names are common genuine given names — they must NOT be
        // reclassified as initials. Only ≤2 chars all-caps are reclassified.
        var r = classify(input);
        assertThat(r.reasonCode())
                .as("3-letter name '%s' should remain NAME_SINGLE_TOKEN", input)
                .isEqualTo(CODE_SINGLE_TOKEN);
        assertThat(r.action()).isEqualTo(Action.SCREEN_RESTRICTED);
    }

    @Test
    void classifyMixedCaseShortName_remainsScreenRestricted() {
        // Mixed-case 2-letter name (e.g. "Pa" — Cambodian/Thai given name entered
        // with natural capitalisation) is NOT reclassified; only all-caps triggers.
        var r = classify("Pa");
        assertThat(r.reasonCode()).isEqualTo(CODE_SINGLE_TOKEN);
        assertThat(r.action()).isEqualTo(Action.SCREEN_RESTRICTED);
    }

    // ── All initials ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"R K B", "A. B. C.", "P Q R S T"})
    void classifyAllInitials_returnsSkipAnomaly(String input) {
        var r = classify(input);
        assertThat(r.reasonCode()).isEqualTo(CODE_ALL_INITIALS);
        assertThat(r.action()).isEqualTo(Action.SKIP_ANOMALY);
    }

    // ── Screenable ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"Kamal Perera", "Mohamed Hassan Ali", "Sahan Jayasinghe"})
    void classifyScreenable_returnsScreenAction(String input) {
        var r = classify(input);
        assertThat(r.reasonCode()).isEqualTo("OK");
        assertThat(r.action()).isEqualTo(Action.SCREEN);
        assertThat(r.screenName()).isEqualTo(input);
    }

    // ── isScreenable / isSkipped helpers ──────────────────────────────────────

    @Test
    void isScreenable_trueForScreenAction() {
        assertThat(classify("Kamal Perera").isScreenable()).isTrue();
    }

    @Test
    void isScreenable_trueForScreenTransformed() {
        assertThat(classify("John Smith(999)").isScreenable()).isTrue();
    }

    @Test
    void isScreenable_falseForSkipped() {
        assertThat(classify("").isScreenable()).isFalse();
    }

    @Test
    void isSkipped_trueForBlank() {
        assertThat(classify("").isSkipped()).isTrue();
    }

    @Test
    void isSkipped_trueForPlaceholder() {
        assertThat(classify("N/A").isSkipped()).isTrue();
    }

    @Test
    void isSkipped_falseForScreenable() {
        assertThat(classify("Kamal Perera").isSkipped()).isFalse();
    }

    // ── Static helper methods ─────────────────────────────────────────────────

    @Test
    void expandDots_replacesDotsWithSpaces() {
        assertThat(expandDots("A.B.C")).isEqualTo("A B C");
        assertThat(expandDots("W.S.Perera")).isEqualTo("W S Perera");
        assertThat(expandDots("noDots")).isEqualTo("noDots");
    }

    @Test
    void splitCamelCase_insertsSpacesAtCaseBoundaries() {
        assertThat(splitCamelCase("KamalPerera")).contains("Kamal").contains("Perera");
        // Result should have at least 2 tokens
        assertThat(splitCamelCase("KamalPerera").trim().split("\\s+").length).isGreaterThanOrEqualTo(2);
    }

    @Test
    void containsInvalidPersonNameChars_detectsDigitsAndBrackets() {
        assertThat(containsInvalidPersonNameChars("Smith(1)")).isTrue();
        assertThat(containsInvalidPersonNameChars("John2Smith")).isTrue();
        assertThat(containsInvalidPersonNameChars("John [X]")).isTrue();
        assertThat(containsInvalidPersonNameChars("John Smith")).isFalse();
        assertThat(containsInvalidPersonNameChars("Abdul-Hamid")).isFalse();
    }

    @Test
    void containsInvalidPersonNameChars_detectsQuestionMarkAndOtherSpecialChars() {
        // ?*~^! are encoding-corruption artefacts in this dataset
        assertThat(containsInvalidPersonNameChars("?A H Kusumsiri????")).isTrue();
        assertThat(containsInvalidPersonNameChars("I?R?MARA")).isTrue();
        assertThat(containsInvalidPersonNameChars("name~suffix")).isTrue();
        assertThat(containsInvalidPersonNameChars("name^suffix")).isTrue();
        assertThat(containsInvalidPersonNameChars("name!suffix")).isTrue();
        // clean names unaffected
        assertThat(containsInvalidPersonNameChars("John Smith")).isFalse();
        assertThat(containsInvalidPersonNameChars("O'Brien")).isFalse();
    }

    // ── Item 1: ? as encoding-corruption character ────────────────────────────

    @Test
    void classifyQuestionMarkLeading_stripsAndScreensTransformed() {
        // "?A H Kusumsiri????" — leading/trailing ? stripped → "A H Kusumsiri"
        // Previously classified SCREEN/OK (? invisible to old regex) → now SCREEN_TRANSFORMED
        var r = classify("?A H Kusumsiri????");
        assertThat(r.reasonCode()).isEqualTo(CODE_STRIPPED_INVALID);
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        assertThat(r.screenName()).isEqualTo("A H Kusumsiri");
    }

    @Test
    void classifyQuestionMarkAsSeparator_stripsAndScreensTransformed() {
        // "I?R?MARA" — ? replaces spaces in encoding-corrupted names
        // Previously SCREEN_RESTRICTED (single-token) → now SCREEN_TRANSFORMED (3 tokens)
        var r = classify("I?R?MARA");
        assertThat(r.reasonCode()).isEqualTo(CODE_STRIPPED_INVALID);
        assertThat(r.action()).isEqualTo(Action.SCREEN_TRANSFORMED);
        // stripping ? and cleaning → multi-token result
        assertThat(r.screenName().trim().split("\\s+").length).isGreaterThanOrEqualTo(2);
    }

    @Test
    void stripInvalidPersonNameChars_removesParenthesesBlocks() {
        String result = stripInvalidPersonNameChars("John Smith(999)");
        assertThat(result.trim()).isEqualTo("John Smith");
    }

    @Test
    void stripInvalidPersonNameChars_removesStandaloneDigits() {
        // digits followed by nothing/space are removed
        String result = stripInvalidPersonNameChars("John 123 Smith");
        assertThat(result.trim()).isEqualTo("John Smith");
    }

    // ── isInitialPlusSurname ──────────────────────────────────────────────────
    // Precondition: input must already be uppercase (as produced by
    // NameRefinerUtil.refineName() in SanctionStrategyBuilder).

    @ParameterizedTest
    @ValueSource(strings = {
        "K CHANDRA",            // classic initial+surname
        "T MANJULA",            // initial+surname (3 customers matched same LXNX entity)
        "M D KANTHI",           // double-initial+surname
        "M. D. KANTHI",         // dotted initials stripped before check
        "N DE SILVA",           // initial + permitted particle "DE" + surname
        "A A RAHMAN",           // double-initial + surname (Pattern B UN case — LXNX still suppressed)
    })
    void isInitialPlusSurname_returnsTrue(String name) {
        assertThat(isInitialPlusSurname(name))
                .as("'%s' should be detected as initial+surname", name)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "K DE ZOYSA WICKRAMASINGHE",  // ZOYSA is not a permitted particle — allowlist not a wildcard
        "J WASANTHA KUMARA",           // WASANTHA is a substantive given name, not an initial
        "M F ABDUL RAHMAN",            // ABDUL is a substantive word (length > 1, not in particles)
        "BASNAYAKE MUDIYANSELAGE",     // first token is not an initial (length > 1)
        "KAMAL PERERA",                // normal two-token name
        "CHANDRA",                     // single token — no surname present
        "K",                           // single token
        "",                            // blank
    })
    void isInitialPlusSurname_returnsFalse(String name) {
        assertThat(isInitialPlusSurname(name))
                .as("'%s' should NOT be detected as initial+surname", name)
                .isFalse();
    }

    @Test
    void isInitialPlusSurname_null_returnsFalse() {
        assertThat(isInitialPlusSurname(null)).isFalse();
    }
}
