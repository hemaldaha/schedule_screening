/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author user
 */
public class ScreeningSolrQueryBuilder {

    /**
     * Constructs a Solr query for the hardened FIU Individual core.
     * <p>
     * This version aligns with the new schema and high-precision requirements:
     * <ul>
     * <li><b>Field Mapping:</b> Targets {@code name_txt} and
     * {@code nic_no_s}.</li>
     * <li><b>Alias Cleaning:</b> Strips "a.k.a" from names to prevent noise
     * matches.</li>
     * <li><b>Recall Strategy:</b> Uses a mix of segment wildcards and fuzzy
     * (~2) matching plus full phrase proximity for scoring boosts.</li>
     * <li><b>ID Precision:</b> Performs exact string matches on NICs, as they
     * are now typed as {@code string} in the new core.</li>
     * </ul>
     * </p>
     */
    public String buildFIUQuery(String cleanName, String oldNic, String newNic) {
        StringBuilder query = new StringBuilder();
        query.append("(");

        // 1. NAME BLOCK (Targets name_txt)
        if (cleanName != null && !cleanName.isBlank()) {
            // Strip "a.k.a" and normalize spaces
            String refinedName = cleanName
                    .replaceAll("(?i)\\ba\\.k\\.a\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            String[] segments = refinedName.split(" ");
            query.append("name_txt:(");

            // Individual Segment Wildcard & Fuzzy
            String segmentLogic = Arrays.stream(segments)
                    .filter(s -> s.replaceAll("\\.", "").trim().length() > 1)
                    .map(s -> s + "* OR " + s + "~2")
                    .collect(Collectors.joining(" OR "));
            query.append(segmentLogic);

            // Full Phrase Logic for high-relevance boosting
            if (segments.length > 1) {
                query.append(" OR \"").append(refinedName).append("\"*");
                query.append(" OR \"").append(refinedName).append("\"~2");
            }
            query.append(")");
        }

        // 2. NIC BLOCK (Targets nic_no_s)
        List<String> nics = new ArrayList<>();
        if (oldNic != null && !oldNic.isBlank()) {
            nics.add(oldNic.trim());
        }
        if (newNic != null && !newNic.isBlank()) {
            nics.add(newNic.trim());
        }

        if (!nics.isEmpty()) {
            if (query.length() > 1) {
                query.append(" OR ");
            }

            query.append("nic_no_s:(");
            query.append(String.join(" OR ", nics));
            query.append(")");
        }

        query.append(")");
        return query.toString();
    }

    /**
     * Constructs a Solr query for the hardened UN Consolidated core.
     * <p>
     * Strategy:
     * <ul>
     * <li><b>Consolidated Name Search:</b> Targets both {@code name_txt} and
     * {@code alias_txt}.</li>
     * <li><b>Fuzzy & Wildcard:</b> Each name segment is expanded to include
     * prefix wildcards and a fuzzy factor (~2) to catch spelling variations
     * (e.g., "Kakorere" vs "Kakolele").</li>
     * <li><b>Proximity Boosting:</b> Uses phrase slop ({@code "~2"}) on the
     * full name to prioritize matches where words appear in the correct
     * sequence.</li>
     * <li><b>Status Filter:</b> Limits results to
     * {@code blacklisted_b:true}.</li>
     * </ul>
     * </p>
     */
    public String buildUNQuery(String cleanName) {
        if (cleanName == null || cleanName.isBlank()) {
            return "";
        }

        String[] segments = cleanName.trim().split("\\s+");

        // 1. Build segment logic: (word1* OR word1~2 OR word2* OR word2~2...)
        String segmentMatches = Arrays.stream(segments)
                .filter(s -> s.replaceAll("\\.", "").trim().length() > 2)
                .map(s -> s + "* OR " + s + "~2")
                .collect(Collectors.joining(" OR "));

        StringBuilder logic = new StringBuilder(segmentMatches);

        // 2. Add full phrase proximity boost
        if (segments.length > 1) {
            logic.append(" OR \"").append(cleanName).append("\"~2");
        }

        String finalLogic = logic.toString();

        // 3. Target name_txt and alias_txt fields
        return "name_txt:(" + finalLogic + ")   " ;//OR alias_txt:(" + finalLogic + "))"; ) AND blacklisted_b:true";
    }

    /**
     * Generates a condensed Solr query targeting the local watch list core
     * using the updated schema.
     * <p>
     * This method maps to the current schema fields:
     * <ul>
     * <li><b>Name Search:</b> Targets {@code name_txt} using double wildcards
     * for partial matches.</li>
     * <li><b>Identity Search:</b> Targets {@code nic_s} (multi-valued string)
     * with exact, wildcard, and fuzzy logic.</li>
     * <li><b>Field Grouping:</b> Maintains the {@code field:(term1 OR term2)}
     * syntax for query efficiency.</li>
     * </ul>
     * </p>
     *
     * @param cleanName The pre-processed customer name segments.
     * @param oldNic The legacy 9-character NIC format.
     * @param newNic The modern 12-character NIC format.
     * @return A formatted Solr query string for the updated local_watch_core.
     */
    public String buildLocalWatchListQuery(String cleanName, String oldNic, String newNic) {
        List<String> queryParts = new ArrayList<>();

        // 1. Name Block - Targeting name_txt (text_general)
        if (cleanName != null && !cleanName.isBlank()) {
            String nameLogic = Arrays.stream(cleanName.split("\\s+"))
                    .map(s -> s.replaceAll("\\.", "").trim())
                    .filter(s -> s.length() > 1)
                    .map(s -> "*" + s + "*")
                    .collect(Collectors.joining(" OR "));

            if (!nameLogic.isBlank()) {
                queryParts.add("name_txt:(" + nameLogic + ")");
            }
        }

        // 2. NIC Block - Targeting nic_s (string)
        List<String> nicVariations = new ArrayList<>();

        if (oldNic != null && !oldNic.isBlank()) {
            String o = oldNic.trim();
            nicVariations.addAll(List.of(o, o + "*", o + "~"));
        }

        if (newNic != null && !newNic.isBlank()) {
            String n = newNic.trim();
            nicVariations.addAll(List.of(n + "*", n + "~10"));
        }

        if (!nicVariations.isEmpty()) {
            queryParts.add("nic_s:(" + String.join(" OR ", nicVariations) + ")");
        }

        return String.join(" OR ", queryParts);
    }

    /**
     * Constructs a Solr query for Organization-based screening targeting the
     * updated FIU core.
     * <p>
     * Optimized for the hardened schema:
     * <ul>
     * <li><b>Field Mapping:</b> Name searches target {@code name_txt};
     * Registration IDs target {@code reference_number_txt}.</li>
     * <li><b>Noise Reduction:</b> Case-insensitive removal of {@code a.k.a} to
     * prevent token-match explosion.</li>
     * <li><b>Literal Matching:</b> Uses quoted segments ({@code "term"}) to
     * ensure Solr treats special characters (dots/dashes) as part of the
     * organizational identity rather than delimiters.</li>
     * <li><b>NA Filtering:</b> Explicitly ignores "NA", null, or empty values
     * for registration numbers.</li>
     * </ul>
     * </p>
     *
     * @param cleanName The organization name (e.g., "Tamil Rehabilitation
     * Organization a.k.a T.R.O").
     * @param registrationNo The registration ID (e.g., "EN/CA/2024/01").
     * @return A structured Solr query string aligned with the hardened schema.
     */
    public String buildFIUOrgQuery(String cleanName, String registrationNo) {
        List<String> queryParts = new ArrayList<>();

        // 1. Organization Name Block (Targets name_txt)
        if (cleanName != null && !cleanName.isBlank()) {
            String refinedOrgName = cleanName
                    .replaceAll("(?i)\\ba\\.k\\.a\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            String nameLogic = Arrays.stream(refinedOrgName.split("\\s+"))
                    .filter(segment -> segment.replaceAll("\\.", "").trim().length() > 1)
                    .map(segment -> "\"" + segment + "\"")
                    .collect(Collectors.joining(" OR "));

            if (!nameLogic.isBlank()) {
                queryParts.add("name_txt:(" + nameLogic + ")");
            }
        }

        // 2. Registration Block (Targets registration_no_s)
        if (registrationNo != null && !registrationNo.equalsIgnoreCase("NA") && !registrationNo.isBlank()) {
            String cleanReg = registrationNo.replaceAll("[^\\p{L}\\p{N}\\-\\\\/]", "").trim();
            if (!cleanReg.isEmpty()) {
                queryParts.add("registration_no_s:\"" + cleanReg + "\"");
            }
        }

        // Global OR wrap
        return (queryParts.size() > 1)
                ? "(" + String.join(") OR (", queryParts) + ")"
                : queryParts.isEmpty() ? "" : queryParts.get(0);
    }
}
