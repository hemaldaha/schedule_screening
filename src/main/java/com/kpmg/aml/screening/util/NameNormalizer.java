/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.util.Arrays;
import java.util.List;

/**
 *
 * @author user
 */
/**
 * Classifies and normalises a raw customer name before AML screening.
 *
 * <p>All algorithms are purely programmatic — no ML, no hardcoded name knowledge.
 * The class is stateless and thread-safe.
 *
 * <p>Classification pipeline (first matching rule wins):
 * <ol>
 *   <li>Blank / null                  → NAME_BLANK               (SKIP_PLACEHOLDER)</li>
 *   <li>Known placeholder text        → NAME_PLACEHOLDER          (SKIP_PLACEHOLDER)</li>
 *   <li>Single-word, dot-separated    → expand dots → screen      (NAME_TRANSFORMED_DOTS)</li>
 *   <li>Single-word, CamelCase        → split at boundaries       (NAME_TRANSFORMED_CAMEL)</li>
 *   <li>Contains digits, brackets, or  → strip → screen or skip    (NAME_INVALID_CHARS_STRIPPED
 *       special chars (?*~^!)                                       / NAME_INVALID_CHARS)</li>
 *   <li>Single token (no surname)     → NAME_SINGLE_TOKEN         (SCREEN_RESTRICTED)</li>
 *   <li>Every token is one initial    → NAME_ALL_INITIALS         (SKIP_ANOMALY)</li>
 *   <li>Otherwise                     → OK                        (SCREEN)</li>
 * </ol>
 *
 * <p>Steps 3–5 are chained for combined cases:
 * {@code "RKWickramarathne(2669)"} → CamelCase split → invalid-char strip → {@code "R K Wickramarathne"}.
 */
public final class NameNormalizer {

    /** Utility class — no instances. */
    private NameNormalizer() {}

    // =========================================================================
    // Stable reason codes
    // IMPORTANT: never rename once in production — these values are stored in the DB.
    // =========================================================================

    /** Name field is empty or null in the source system. */
    public static final String CODE_BLANK             = "NAME_BLANK";
    /** Literal placeholder entered (e.g. "NOT MENTIONED", "N/A"). */
    public static final String CODE_PLACEHOLDER       = "NAME_PLACEHOLDER";
    /** Only one name token — no surname present. */
    public static final String CODE_SINGLE_TOKEN      = "NAME_SINGLE_TOKEN";
    /** Every token is a single initial — no usable surname. */
    public static final String CODE_ALL_INITIALS      = "NAME_ALL_INITIALS";
    /** Contains digits/brackets; insufficient tokens after stripping. */
    public static final String CODE_INVALID_CHARS     = "NAME_INVALID_CHARS";
    /** Dots used as separators — expanded to spaces before screening. */
    public static final String CODE_TRANSFORMED_DOTS  = "NAME_TRANSFORMED_DOTS";
    /** CamelCase concatenation — split to spaced tokens before screening. */
    public static final String CODE_TRANSFORMED_CAMEL = "NAME_TRANSFORMED_CAMEL";
    /** Digits/brackets stripped from personal name before screening. */
    public static final String CODE_STRIPPED_INVALID  = "NAME_INVALID_CHARS_STRIPPED";

    // =========================================================================
    // Placeholder values (exact uppercase match)
    // =========================================================================

    private static final List<String> PLACEHOLDERS = List.of(
            "NOT MENTIONED", "NOT AVAILABLE", "NOT APPLICABLE",
            "UNKNOWN", "N/A", "NA", "NONE", "-", "."
    );

    // =========================================================================
    // Public types
    // =========================================================================

    /** What to do with a customer record after name classification. */
    public enum Action {
        /** Screen with the original name (no transformation). */
        SCREEN,
        /** Screen with the normalised name (transformation applied). */
        SCREEN_TRANSFORMED,
        /**
         * Screen with a restricted query (exact + fuzzy only, no wildcard).
         * Applied to single-token names — they must be screened but with tight
         * precision controls to prevent noise on large corpora.
         * See AML_EXCEPTION_ENHANSEMENT.MD §5 for regulatory rationale.
         */
        SCREEN_RESTRICTED,
        /** Name is a data-entry placeholder — cannot be screened until remediated. */
        SKIP_PLACEHOLDER,
        /** Name is an anomaly (all-initials, invalid chars) — cannot be screened reliably. */
        SKIP_ANOMALY
    }

    /**
     * Immutable result of name classification.
     *
     * <p>Mapping to the {@code screening_exceptions} DB table:
     * <ul>
     *   <li>{@code reasonCode} → {@code reason_code} column</li>
     *   <li>{@code action}     → drives {@code action} column value</li>
     *   <li>{@code note}       → {@code review_notes} column (compliance team)</li>
     * </ul>
     * Records with {@code action == SCREEN} are not written to the exceptions log.
     *
     * @param screenName  Name to submit to the query builder — meaningful only when
     *                    {@code action} is SCREEN or SCREEN_TRANSFORMED.
     * @param action      Disposition.
     * @param reasonCode  Stable DB value — use the CODE_* constants.
     * @param note        Human-readable explanation for the data-quality / compliance team.
     */
    public record Result(
            String screenName,
            Action action,
            String reasonCode,
            String note
    ) {
        /** True if this customer should be submitted to Solr. */
        public boolean isScreenable() {
            return action == Action.SCREEN || action == Action.SCREEN_TRANSFORMED;
        }

        /** True if this customer was skipped and requires compliance review. */
        public boolean isSkipped() {
            return action == Action.SKIP_PLACEHOLDER || action == Action.SKIP_ANOMALY;
        }
    }

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Classifies and, where possible, normalises a raw customer name.
     *
     * @param rawName  raw value from the source system (may be null / blank)
     * @return a non-null {@link Result}
     */
    public static Result classify(String rawName) {

        // ── 1. Blank / null ───────────────────────────────────────────────────
        if (rawName == null || rawName.isBlank())
            return new Result(
                    rawName == null ? "" : rawName,
                    Action.SKIP_PLACEHOLDER,
                    CODE_BLANK,
                    "Name field is blank or null");

        String trimmed = rawName.trim();
        String upper   = trimmed.toUpperCase();

        // ── 2. Known placeholder ──────────────────────────────────────────────
        if (PLACEHOLDERS.contains(upper))
            return new Result(trimmed, Action.SKIP_PLACEHOLDER, CODE_PLACEHOLDER,
                    "Data-entry placeholder: " + trimmed);

        // ── 2b. Strip punctuation-only tokens ─────────────────────────────────
        // Handles data-entry artefacts where commas, dashes, etc. appear as
        // isolated tokens: ", SIRITHARAN" → "SIRITHARAN", "AMARASINGHE -" → "AMARASINGHE".
        // Only tokens containing zero Unicode letters are removed; intra-word
        // hyphens ("ANNE-MARIE") and dotted initials ("D.") are unaffected because
        // they contain at least one letter.
        String sanitized = removePunctOnlyTokens(trimmed);
        if (!sanitized.equals(trimmed)) {
            if (sanitized.isBlank())
                return new Result(trimmed, Action.SKIP_PLACEHOLDER, CODE_PLACEHOLDER,
                        "Name reduces to nothing after punctuation-only token removal: " + trimmed);
            trimmed = sanitized;
            upper   = trimmed.toUpperCase();
        }

        // ── 3. Single-word dot-separated → expand dots to spaces ─────────────
        if (!trimmed.contains(" ") && trimmed.contains(".")) {
            String expanded = expandDots(trimmed);
            if (tokenCount(expanded) >= 2) {
                if (containsInvalidPersonNameChars(expanded)) {
                    String stripped = stripInvalidPersonNameChars(expanded);
                    if (tokenCount(stripped) >= 2)
                        return new Result(stripped, Action.SCREEN_TRANSFORMED, CODE_STRIPPED_INVALID,
                                "Dots expanded then digits/brackets stripped: ["
                                + trimmed + "] → [" + expanded + "] → [" + stripped + "]");
                    return new Result(trimmed, Action.SKIP_ANOMALY, CODE_INVALID_CHARS,
                            "Name contains digits/brackets; insufficient usable tokens after stripping: "
                            + trimmed);
                }
                return new Result(expanded, Action.SCREEN_TRANSFORMED, CODE_TRANSFORMED_DOTS,
                        "Dots expanded to spaces: [" + trimmed + "] → [" + expanded + "]");
            }
            trimmed = expanded;
            upper   = trimmed.toUpperCase();
        }

        // ── 4. Single-word CamelCase → split at case boundaries ──────────────
        if (!trimmed.contains(" ") && hasMixedCase(trimmed)) {
            String split = splitCamelCase(trimmed);
            if (tokenCount(split) >= 2) {
                if (containsInvalidPersonNameChars(split)) {
                    String stripped = stripInvalidPersonNameChars(split);
                    if (tokenCount(stripped) >= 2)
                        return new Result(stripped, Action.SCREEN_TRANSFORMED, CODE_STRIPPED_INVALID,
                                "CamelCase split then digits/brackets stripped: ["
                                + trimmed + "] → [" + split + "] → [" + stripped + "]");
                    return new Result(trimmed, Action.SKIP_ANOMALY, CODE_INVALID_CHARS,
                            "Name contains digits/brackets; insufficient usable tokens after stripping: "
                            + trimmed);
                }
                return new Result(split, Action.SCREEN_TRANSFORMED, CODE_TRANSFORMED_CAMEL,
                        "CamelCase split at case boundaries: [" + trimmed + "] → [" + split + "]");
            }
        }

        // ── 5. Digits or brackets in a multi-word name ────────────────────────
        if (containsInvalidPersonNameChars(trimmed)) {
            String stripped = stripInvalidPersonNameChars(trimmed);
            if (tokenCount(stripped) >= 2)
                return new Result(stripped, Action.SCREEN_TRANSFORMED, CODE_STRIPPED_INVALID,
                        "Digits/brackets stripped from personal name: [" + trimmed + "] → [" + stripped + "]");
            return new Result(trimmed, Action.SKIP_ANOMALY, CODE_INVALID_CHARS,
                    "Name contains digits/brackets; insufficient usable tokens after stripping: "
                    + trimmed);
        }

        // ── 6. Single-token — screen with restricted query ────────────────────
        // Single-name entries exist on live sanction lists (UN, OFAC, FIU).
        // FATF 2025 prohibits wholesale exclusion without documented rationale.
        // Restricted query: exact phrase + fuzzy~1 + phonetic on lite cores;
        // exact phrase only on heavy corpora (7M+ records).
        // See AML_EXCEPTION_ENHANSEMENT.MD §5 for full regulatory rationale.
        if (tokenCount(trimmed) == 1) {
            // A single token of 1–2 all-uppercase letters is almost certainly
            // stored initials (e.g. "PA" for "P.A. Perera"), not a genuine
            // monosyllabic given name.  Route to NAME_ALL_INITIALS so that
            // SanctionStrategyBuilder can apply NIC_ONLY (if NIC present) or
            // SKIPPED, rather than generating a RESTRICTED-mode LXNX query
            // that surfaces every sanctioned "Pa" in the global corpus.
            // Mixed-case short names ("Pa", "pa") are left as NAME_SINGLE_TOKEN
            // because they more plausibly represent a real given name entered
            // with natural capitalisation.
            if (trimmed.length() <= 2 && trimmed.matches("[A-Z]+"))
                return new Result(trimmed, Action.SKIP_ANOMALY, CODE_ALL_INITIALS,
                        "Short all-caps single token (≤2 letters) — treated as initials abbreviation: " + trimmed);
            return new Result(trimmed, Action.SCREEN_RESTRICTED, CODE_SINGLE_TOKEN,
                    "Single-name record — restricted query applied (exact+fuzzy, no wildcard): " + trimmed);
        }

        // ── 7. All-initials (no usable surname) ───────────────────────────────
        // NIC-only screening may still apply — see SanctionStrategyBuilder.determineModeFor().
        if (allInitials(trimmed))
            return new Result(trimmed, Action.SKIP_ANOMALY, CODE_ALL_INITIALS,
                    "Every token is a single initial — no usable surname: " + trimmed);

        // ── 8. Screenable as-is ───────────────────────────────────────────────
        return new Result(trimmed, Action.SCREEN, "OK", "");
    }

    // =========================================================================
    // Programmatic transformation algorithms (public for unit testing)
    // =========================================================================

    /**
     * Permitted non-initial, non-final tokens for {@link #isInitialPlusSurname}.
     *
     * <p>"DE" is a zero-discrimination preposition preceding extremely common Sri Lankan
     * surnames (DE SILVA, DE MEL, DE SARAM, DE ALWIS). A customer named "N DE SILVA"
     * is structurally identical to "K CHANDRA" for LXNX screening purposes: the
     * initial provides no identity discrimination in the 7M+ LXNX corpus.
     *
     * <p>Do NOT add entries without a separately justified, data-backed case.
     * Confirmed against 284 alerts from run 20260715_001104: exactly one particle
     * ("DE") required.
     */
    private static final java.util.Set<String> PERMITTED_PARTICLES = java.util.Set.of("DE");

    /**
     * Returns {@code true} when the name follows INITIAL+SURNAME format: every non-final
     * token is either a single letter (possibly with a trailing dot) or an entry in
     * {@link #PERMITTED_PARTICLES}; and the final token is a substantive word longer
     * than one character after dot-stripping.
     *
     * <p><b>Precondition — uppercase input only.</b> The particle comparison is
     * case-sensitive against the uppercase entries in {@link #PERMITTED_PARTICLES}.
     * In the production pipeline this contract is always satisfied because
     * {@code SanctionStrategyBuilder} calls this method on {@code nameToScreen},
     * which is the output of {@code NameRefinerUtil.refineName()} (calls
     * {@code toUpperCase()} unconditionally). Passing mixed-case input is a caller
     * contract violation.
     *
     * <p>Examples returning {@code true}: {@code "K CHANDRA"}, {@code "T MANJULA"},
     * {@code "M D KANTHI"}, {@code "N DE SILVA"}.
     *
     * <p>Examples returning {@code false}: {@code "K DE ZOYSA WICKRAMASINGHE"}
     * (ZOYSA is not a permitted particle — allowlist is not a wildcard for middle
     * tokens), {@code "J WASANTHA KUMARA"} (WASANTHA is a substantive given name),
     * {@code "BASNAYAKE MUDIYANSELAGE"} (first token is not an initial).
     *
     * <p>Used by {@code SanctionStrategyBuilder} to route LXNX queries to audit-only
     * for these customers: the 7M+ LXNX corpus stores individual aliases in
     * "K. Chandra" and "R, Krishnakumar" format, producing structural false-positive
     * 100% matches for any "K CHANDRA" or "R KRISHNAKUMAR" customer.
     */
    public static boolean isInitialPlusSurname(String name) {
        if (name == null || name.isBlank()) return false;
        String[] tokens = name.trim().split("\\s+");
        if (tokens.length < 2) return false;
        for (int i = 0; i < tokens.length - 1; i++) {
            String t = tokens[i].replace(".", "").trim();
            if (t.length() != 1 && !PERMITTED_PARTICLES.contains(t)) return false;
        }
        String lastToken = tokens[tokens.length - 1].replace(".", "").trim();
        return lastToken.length() > 1;
    }

    public static String expandDots(String name) {
        return name.replace(".", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    public static String splitCamelCase(String name) {
        return name
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")  // pass 1
                .replaceAll("([A-Z])(?=[A-Z])",      "$1 ")    // pass 2
                .replaceAll("([a-z])([A-Z])",         "$1 $2") // pass 3
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean containsInvalidPersonNameChars(String name) {
        return name.matches(".*[\\d()\\[\\]?*~^!].*");
    }

    public static String stripInvalidPersonNameChars(String name) {
        return name
                .replaceAll("\\(.*?\\)", "")        // remove (...) blocks
                .replaceAll("\\[.*?\\]", "")        // remove [...] blocks
                .replaceAll("\\d+[A-Za-z]?", "")   // remove digit sequences (+ optional NIC suffix V/X)
                .replaceAll("[^\\p{L}\\s]", " ")    // remaining punctuation → space
                .replaceAll("\\s+", " ")
                .trim();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static boolean hasMixedCase(String s) {
        return s.matches(".*[A-Z].*") && s.matches(".*[a-z].*");
    }

    private static int tokenCount(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.trim().split("\\s+").length;
    }

    private static boolean allInitials(String s) {
        return Arrays.stream(s.trim().split("\\s+"))
                     .allMatch(t -> t.replace(".", "").trim().length() <= 1);
    }

    /**
     * Removes tokens that contain no Unicode letter at all (e.g. standalone
     * commas, dashes, or other punctuation inserted by the source system).
     * Tokens that contain at least one letter — including dotted initials
     * like "D." or hyphenated words like "ANNE-MARIE" — are preserved.
     */
    private static String removePunctOnlyTokens(String name) {
        StringBuilder sb = new StringBuilder();
        for (String token : name.split("\\s+")) {
            if (token.matches(".*\\p{L}.*")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(token);
            }
        }
        return sb.toString();
    }
}