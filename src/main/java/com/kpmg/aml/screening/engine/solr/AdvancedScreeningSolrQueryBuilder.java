/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author user
 */

/**
 * Replaces ScreeningSolrQueryBuilder — improved precision and recall.
 *
 * Schema reference:
 *   fiu_individual_core  — name_txt, name_phonetic*, nic_no_s, passport_no_ss
 *   fiu_org_core         — name_txt, name_phonetic*, registration_no_s
 *   un_consolidated_core — name_txt†, alias_txt†, name_phonetic*
 *   local_watch_core     — name_txt†, alias_txt†, name_phonetic*, nic_s
 *   lxnx_entities_core   — search_names_txt†, name_phonetic*
 *
 *   * copyField target (DoubleMetaphone, inject=false)  † multiValued
 *
 * Fully tested against 25,000 customers across all cores.
 * See AML_EXCEPTION_ENHANSEMENT.MD for design decisions.
 */
public class AdvancedScreeningSolrQueryBuilder {

    // =========================================================================
    // Helpers
    // =========================================================================

    private static final String SOLR_SPECIAL = "+-&&||!(){}[]^\"~*?:\\/";

    private static String escapeSolr(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (SOLR_SPECIAL.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Adaptive wildcard+fuzzy clause for one lowercase token.
     *   stripped len < 4  → wildcard only
     *   stripped len 4–6  → t* OR t~1
     *   stripped len ≥ 7  → t* OR t~2
     */
    private static String termClause(String s) {
        String t = s.toLowerCase();
        int len = t.replace(".", "").trim().length();
        if (len < 4) return t + "*";
        if (len < 7) return t + "* OR " + t + "~1";
        return               t + "* OR " + t + "~2";
    }

    /** Keeps tokens with stripped length > 3 (drops particles/initials). */
    private static List<String> significantTokens(String name) {
        return Arrays.stream(name.toLowerCase().split("\\s+"))
                .filter(s -> s.replace(".", "").trim().length() > 3)
                .collect(Collectors.toList());
    }

    /**
     * Phonetic clause with AND semantics (+token prefix).
     * Punctuation-only tokens are stripped to prevent HTTP 400 from Solr.
     * Returns "" if no alphanumeric tokens remain.
     */
    private static String phoneticClause(String field, String name) {
        String[] tokens = Arrays.stream(name.trim().split("\\s+"))
                .filter(t -> t.replaceAll("[^\\p{L}\\p{N}]", "").length() > 0)
                .toArray(String[]::new);
        if (tokens.length == 0) return "";
        if (tokens.length == 1) return field + ":(" + tokens[0] + ")";
        String mandatory = Arrays.stream(tokens)
                .map(t -> "+" + t)
                .collect(Collectors.joining(" "));
        return field + ":(" + mandatory + ")";
    }

    /**
     * Builds name_txt clause:
     *   - Single token:    tok* [OR tok~N]
     *   - Multiple tokens: (tok1 AND tok2) OR "Full Name"~2
     *     Uses 2 lexically distinct anchors (dedup + edit-distance check).
     */
    private static String buildNameTxtClause(String field, String name) {
        List<String> tokens = significantTokens(name);
        if (tokens.isEmpty()) return "";

        if (tokens.size() == 1) {
            String trimmed = name.trim();
            if (trimmed.contains(" ")) {
                return field + ":((" + termClause(tokens.get(0)) + ") OR \"" + trimmed + "\"~2)";
            }
            return field + ":(" + termClause(tokens.get(0)) + ")";
        }

        List<String> sorted = tokens.stream()
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .collect(Collectors.toList());

        if (sorted.isEmpty()) return "";
        if (sorted.size() == 1) {
            return field + ":((" + termClause(sorted.get(0)) + ") OR \"" + name.trim() + "\"~2)";
        }

        String a1 = sorted.get(0);
        String a2 = null;
        for (int i = 1; i < sorted.size(); i++) {
            if (editDistance(a1, sorted.get(i), 2) > 2) { a2 = sorted.get(i); break; }
        }
        if (a2 == null) {
            return field + ":\"" + name.trim() + "\"~2";
        }

        String andClause = "(" + termClause(a1) + ") AND (" + termClause(a2) + ")";
        return field + ":((" + andClause + ") OR \"" + name.trim() + "\"~2)";
    }

    // =========================================================================
    // 1. FIU Individual Core
    // =========================================================================

    public String buildFIUQuery(String cleanName, String oldNic, String newNic, String passportNo) {
        List<String> parts = new ArrayList<>();

        if (cleanName != null && !cleanName.isBlank()) {
            String refined = cleanName.replaceAll("(?i)\\ba\\.k\\.a\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            String nameTxt = buildNameTxtClause("name_txt", refined);
            if (!nameTxt.isBlank()) parts.add(nameTxt);
            String ph = phoneticClause("name_phonetic", refined);
            if (!ph.isBlank()) parts.add(ph);
        }

        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank())
            nicTerms.add("\"" + escapeSolr(oldNic.trim()) + "\"");
        if (newNic != null && !newNic.isBlank())
            nicTerms.add("\"" + escapeSolr(newNic.trim()) + "\"");
        if (!nicTerms.isEmpty())
            parts.add("nic_no_s:(" + String.join(" OR ", nicTerms) + ")");

        if (passportNo != null && !passportNo.isBlank())
            parts.add("passport_no_ss:\"" + escapeSolr(passportNo.trim()) + "\"");

        if (parts.isEmpty()) return "";
        return "(" + String.join(" OR ", parts) + ")";
    }

    public String buildFIUQuery(String cleanName, String oldNic, String newNic) {
        return buildFIUQuery(cleanName, oldNic, newNic, null);
    }

    // =========================================================================
    // 2. UN Consolidated Core
    // =========================================================================

    public String buildUNQuery(String cleanName) {
        if (cleanName == null || cleanName.isBlank()) return "";
        String trimmed = cleanName.trim();
        List<String> parts = new ArrayList<>();
        String nameTxt  = buildNameTxtClause("name_txt",  trimmed);
        String aliasTxt = buildNameTxtClause("alias_txt", trimmed);
        if (!nameTxt.isBlank())  parts.add(nameTxt);
        if (!aliasTxt.isBlank()) parts.add(aliasTxt);
        String ph = phoneticClause("name_phonetic", trimmed);
        if (!ph.isBlank()) parts.add(ph);
        return "(" + String.join(" OR ", parts) + ")";
    }

    // =========================================================================
    // 3. Local Watch Core
    // =========================================================================

    public String buildLocalWatchListQuery(String cleanName, String oldNic, String newNic) {
        List<String> parts = new ArrayList<>();
        if (cleanName != null && !cleanName.isBlank()) {
            String trimmed = cleanName.trim();
            String nameTxt  = buildNameTxtClause("name_txt",  trimmed);
            String aliasTxt = buildNameTxtClause("alias_txt", trimmed);
            if (!nameTxt.isBlank())  parts.add(nameTxt);
            if (!aliasTxt.isBlank()) parts.add(aliasTxt);
            String ph = phoneticClause("name_phonetic", trimmed);
            if (!ph.isBlank()) parts.add(ph);
        }
        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank()) {
            String o = escapeSolr(oldNic.trim());
            nicTerms.addAll(List.of(o, o + "*", o + "~1"));
        }
        if (newNic != null && !newNic.isBlank()) {
            String n = escapeSolr(newNic.trim());
            nicTerms.addAll(List.of(n, n + "*", n + "~1"));
        }
        if (!nicTerms.isEmpty())
            parts.add("nic_s:(" + String.join(" OR ", nicTerms) + ")");
        return String.join(" OR ", parts);
    }

    // =========================================================================
    // 4. FIU Org Core
    // =========================================================================

    public String buildFIUOrgQuery(String cleanName, String registrationNo) {
        List<String> parts = new ArrayList<>();
        if (cleanName != null && !cleanName.isBlank()) {
            String refined = cleanName.replaceAll("(?i)\\ba\\.k\\.a\\b", " ")
                    .replaceAll("\\s+", " ").trim();
            List<String> sigTokens = Arrays.stream(refined.split("\\s+"))
                    .filter(s -> s.replace(".", "").trim().length() > 5)
                    .collect(Collectors.toList());
            if (sigTokens.size() >= 2) {
                String nameLogic = sigTokens.stream()
                        .map(s -> "\"" + s + "\"")
                        .collect(Collectors.joining(" AND "));
                parts.add("name_txt:(" + nameLogic + ")");
            } else {
                parts.add("name_txt:\"" + refined + "\"~2");
            }
            // Phonetic intentionally omitted for fiu_org — DM codes produce FP on org names.
        }
        if (registrationNo != null
                && !registrationNo.equalsIgnoreCase("NA")
                && !registrationNo.isBlank()) {
            String cleanReg = registrationNo
                    .replaceAll("[^\\p{L}\\p{N}\\-\\/]", "").trim();
            if (!cleanReg.isEmpty())
                parts.add("registration_no_s:\"" + escapeSolr(cleanReg) + "\"");
        }
        if (parts.isEmpty()) return "";
        return (parts.size() == 1) ? parts.get(0)
                : "(" + String.join(") OR (", parts) + ")";
    }

    // =========================================================================
    // 5. LXNX Entities Core  (heavy cluster — 7M+ records)
    // =========================================================================

    private static int editDistance(String a, String b, int maxDist) {
        if (Math.abs(a.length() - b.length()) > maxDist) return maxDist + 1;
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= n; j++) {
                curr[j] = (a.charAt(i - 1) == b.charAt(j - 1))
                        ? prev[j - 1]
                        : 1 + Math.min(prev[j - 1], Math.min(prev[j], curr[j - 1]));
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > maxDist) return maxDist + 1;
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    private static List<String> sigTokensHeavy(String lower) {
        return Arrays.stream(lower.split("\\s+"))
                .filter(s -> s.replace(".", "").trim().length() > 5)
                .collect(Collectors.toList());
    }

    /**
     * Builds a wildcard anchor for one lowercase sig-token.
     * If the token ends in 'a' and the stem (token minus trailing 'a') is ≥ 5 chars,
     * the stem is added as an exact alternative to capture the shorter Indian/South Asian
     * transliteration form: e.g. "kumara" → "(kumara* OR kumar)".
     */
    private static String anchorClause(String token) {
        if (token.endsWith("a") && token.length() - 1 >= 5) {
            String stem = token.substring(0, token.length() - 1);
            return "(" + token + "* OR " + stem + ")";
        }
        return token + "*";
    }

    private static String buildHeavyNameClause(String txtField, String lower) {
        List<String> sig = sigTokensHeavy(lower);
        List<String> sigDistinct = sig.stream().distinct().collect(Collectors.toList());

        if (sigDistinct.size() >= 2) {
            List<String> sorted = sigDistinct.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .collect(Collectors.toList());
            String a1 = sorted.get(0);
            String a2 = null;
            for (int i = 1; i < sorted.size(); i++) {
                if (editDistance(a1, sorted.get(i), 2) > 2) { a2 = sorted.get(i); break; }
            }
            if (a2 != null) {
                String andClause = "(" + anchorClause(a1) + ") AND (" + anchorClause(a2) + ")";
                return txtField + ":((" + andClause + ") OR \"" + lower + "\"~2)";
            }
        }
        if (sigDistinct.size() == 1) {
            String t = sigDistinct.get(0);
            if (!lower.contains(" ")) return txtField + ":(" + anchorClause(t) + ")";
            return txtField + ":\"" + lower + "\"~2";
        }
        return txtField + ":\"" + lower + "\"~2";
    }

    public String buildLXNXQuery(String cleanName) {
        if (cleanName == null || cleanName.isBlank()) return "";
        return buildHeavyNameClause("search_names_txt", cleanName.trim().toLowerCase());
    }

    // =========================================================================
    // Restricted query methods — single-token names (ScreeningMode.RESTRICTED)
    //
    // Lite cores (~200–500 docs): exact phrase + fuzzy~1 + phonetic.
    // Heavy cores (7M+ records):  exact phrase ONLY — no noise budget.
    // FIU-ORG: NOT_APPLICABLE — person name vs org list.
    // =========================================================================

    /** Restricted FIU Individual: exact + fuzzy~1 + phonetic. NIC/passport as secondary OR. */
    public String buildRestrictedFIUQuery(String name, String oldNic, String newNic, String passportNo) {
        if (name == null || name.isBlank()) return "";
        List<String> parts = new ArrayList<>();
        String t = name.trim(), low = t.toLowerCase();
        parts.add("name_txt:(\"" + escapeSolr(t) + "\" OR " + low + "~1)");
        String ph = phoneticClause("name_phonetic", t);
        if (!ph.isBlank()) parts.add(ph);
        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank())
            nicTerms.add("\"" + escapeSolr(oldNic.trim()) + "\"");
        if (newNic != null && !newNic.isBlank())
            nicTerms.add("\"" + escapeSolr(newNic.trim()) + "\"");
        if (!nicTerms.isEmpty())
            parts.add("nic_no_s:(" + String.join(" OR ", nicTerms) + ")");
        if (passportNo != null && !passportNo.isBlank())
            parts.add("passport_no_ss:\"" + escapeSolr(passportNo.trim()) + "\"");
        return "(" + String.join(" OR ", parts) + ")";
    }

    /** Restricted UN Consolidated: exact + fuzzy~1 + phonetic on name_txt and alias_txt. */
    public String buildRestrictedUNQuery(String name) {
        if (name == null || name.isBlank()) return "";
        String t = name.trim(), low = t.toLowerCase();
        List<String> parts = new ArrayList<>();
        parts.add("name_txt:(\"" + escapeSolr(t) + "\" OR " + low + "~1)");
        parts.add("alias_txt:(\"" + escapeSolr(t) + "\" OR " + low + "~1)");
        String ph = phoneticClause("name_phonetic", t);
        if (!ph.isBlank()) parts.add(ph);
        return "(" + String.join(" OR ", parts) + ")";
    }

    /** Restricted Local Watch: exact + fuzzy~1 + phonetic. NIC as secondary OR. */
    public String buildRestrictedLocalWatchQuery(String name, String oldNic, String newNic) {
        List<String> parts = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            String t = name.trim(), low = t.toLowerCase();
            parts.add("name_txt:(\"" + escapeSolr(t) + "\" OR " + low + "~1)");
            parts.add("alias_txt:(\"" + escapeSolr(t) + "\" OR " + low + "~1)");
            String ph = phoneticClause("name_phonetic", t);
            if (!ph.isBlank()) parts.add(ph);
        }
        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank()) nicTerms.add(escapeSolr(oldNic.trim()));
        if (newNic != null && !newNic.isBlank()) nicTerms.add(escapeSolr(newNic.trim()));
        if (!nicTerms.isEmpty())
            parts.add("nic_s:(" + String.join(" OR ", nicTerms) + ")");
        return String.join(" OR ", parts);
    }

    /** Restricted LXNX: exact phrase only — no fuzzy, no wildcard, no phonetic. */
    public String buildRestrictedLXNXQuery(String name) {
        if (name == null || name.isBlank()) return "";
        return "search_names_txt:\"" + name.trim().toLowerCase() + "\"";
    }

    // =========================================================================
    // NIC-only query methods — all-initials names (ScreeningMode.NIC_ONLY)
    //
    // Initials carry no screening value. NIC uniquely identifies a Sri Lankan
    // citizen — used on FIU_IND and LOCAL_WATCH which carry NIC fields.
    // =========================================================================

    /** NIC-only FIU Individual: nic_no_s + passport_no_ss only. No name clause. */
    public String buildNicOnlyFIUQuery(String oldNic, String newNic, String passportNo) {
        List<String> parts = new ArrayList<>();
        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank())
            nicTerms.add("\"" + escapeSolr(oldNic.trim()) + "\"");
        if (newNic != null && !newNic.isBlank())
            nicTerms.add("\"" + escapeSolr(newNic.trim()) + "\"");
        if (!nicTerms.isEmpty())
            parts.add("nic_no_s:(" + String.join(" OR ", nicTerms) + ")");
        if (passportNo != null && !passportNo.isBlank())
            parts.add("passport_no_ss:\"" + escapeSolr(passportNo.trim()) + "\"");
        if (parts.isEmpty()) return "";
        return "(" + String.join(" OR ", parts) + ")";
    }

    /** NIC-only Local Watch: nic_s only. No name clause. */
    public String buildNicOnlyLocalWatchQuery(String oldNic, String newNic) {
        List<String> nicTerms = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank()) nicTerms.add(escapeSolr(oldNic.trim()));
        if (newNic != null && !newNic.isBlank()) nicTerms.add(escapeSolr(newNic.trim()));
        if (nicTerms.isEmpty()) return "";
        return "nic_s:(" + String.join(" OR ", nicTerms) + ")";
    }
}