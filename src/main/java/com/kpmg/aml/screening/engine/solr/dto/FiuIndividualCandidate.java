/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr.dto;

import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.solr.common.SolrDocument;

/**
 *
 * @author user
 */
public class FiuIndividualCandidate extends ScreeningCandidate {

    private final List<String> nameVariants;
    private final List<String> idDocuments;

    public FiuIndividualCandidate(SolrDocument doc) {
        super(
            (String) doc.getFieldValue("id"),
            "fiu_individual_core"
        );
        this.nameVariants = extractNameVariants(doc);
        this.idDocuments  = extractIdDocuments(doc);
    }

    private List<String> extractNameVariants(SolrDocument doc) {
        List<String> variants = new ArrayList<>();

        // name_txt is single-value but embeds aliases inline:
        // "Raman Sinnappa a.k.a Sinnappa Master" → split into discrete variants
        Object nameVal = doc.getFieldValue("name_txt");
        if (nameVal != null) {
            Arrays.stream(nameVal.toString().split("(?i)a\\.k\\.a"))
                  .map(String::trim)
                  .filter(s -> !s.isBlank())
                  .forEach(variants::add);
        }

        // title_txt and designation_txt: sparse in real data but valid name signals
        addIfPresent(variants, doc, "title_txt");
        addIfPresent(variants, doc, "designation_txt");

        // name_phonetic: stored:false in this core — never returned by Solr, skip

        // other_information_txt: long free-text (Interpol notices etc.) — skip,
        // not a name signal and would corrupt scoring

        return variants;
    }

    private List<String> extractIdDocuments(SolrDocument doc) {
        List<String> ids = new ArrayList<>();

        // nic_no_s: single value, may contain internal spaces e.g. "681202358 V"
        // kept as-is — NIC normalisation happens upstream in the query builder
        addIfPresent(ids, doc, "nic_no_s");

        // passport_no_ss: multivalue BUT some elements pack multiple passports
        // with embedded newlines e.g. "N 1643385\nN 1426422" — split each element
        Object passportVal = doc.getFieldValue("passport_no_ss");
        if (passportVal instanceof List<?> passportList) {
            for (Object p : passportList) {
                Arrays.stream(p.toString().split("\\n"))
                      .map(String::trim)
                      .filter(s -> !s.isBlank())
                      .forEach(ids::add);
            }
        } else if (passportVal != null) {
            Arrays.stream(passportVal.toString().split("\\n"))
                  .map(String::trim)
                  .filter(s -> !s.isBlank())
                  .forEach(ids::add);
        }

        addIfPresent(ids, doc, "reference_number_s");

        return ids;
    }

    @Override public List<String> getNameVariants() { return nameVariants; }
    @Override public List<String> getIdDocuments()  { return idDocuments; }
}
