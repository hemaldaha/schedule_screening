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
public class FiuOrgCandidate extends ScreeningCandidate {

    private static final String NA = "NA";

    private final List<String> nameVariants;
    private final List<String> idDocuments;

    public FiuOrgCandidate(SolrDocument doc) {
        super(
            (String) doc.getFieldValue("id"),
            "fiu_org_core"
        );
        this.nameVariants = extractNameVariants(doc);
        this.idDocuments  = extractIdDocuments(doc);
    }

    private List<String> extractNameVariants(SolrDocument doc) {
        List<String> variants = new ArrayList<>();

        // name_txt is multivalue in fiu_org and embeds inline a.k.a aliases:
        // ["Liberation Tigers of Tamil Eelam a.k.a L.T.T. E a.k.a TAMIL TIGERS"]
        // Split each element on a.k.a to get discrete variants
        Object nameVal = doc.getFieldValue("name_txt");
        if (nameVal instanceof List<?> nameList) {
            for (Object n : nameList) {
                Arrays.stream(n.toString().split("(?i)a\\.k\\.a"))
                      .map(String::trim)
                      .filter(s -> !s.isBlank())
                      .forEach(variants::add);
            }
        } else if (nameVal != null) {
            Arrays.stream(nameVal.toString().split("(?i)a\\.k\\.a"))
                  .map(String::trim)
                  .filter(s -> !s.isBlank())
                  .forEach(variants::add);
        }

        // name_phonetic: identical duplicate of name_txt in real data — skip,
        // adds zero new information and would double every variant

        // address_txt: present but many are "NA" — filter those out
        Object addrVal = doc.getFieldValue("address_txt");
        if (addrVal != null && !NA.equalsIgnoreCase(addrVal.toString().trim())) {
            variants.add(addrVal.toString().trim());
        }

        return variants;
    }

    private List<String> extractIdDocuments(SolrDocument doc) {
        List<String> ids = new ArrayList<>();

        // nic_s and document_txt: in schema but absent in real data — no-ops
        addMultiIfPresent(ids, doc, "nic_s");
        addMultiIfPresent(ids, doc, "document_txt");

        // registration_no_s: present in real data (note: schema listed reference_number_txt
        // but real documents use registration_no_s — using the actual field name)
        // filter "NA" values which appear frequently
        Object regVal = doc.getFieldValue("registration_no_s");
        if (regVal != null && !NA.equalsIgnoreCase(regVal.toString().trim())) {
            ids.add(regVal.toString().trim());
        }

        return ids;
    }

    @Override public List<String> getNameVariants() { return nameVariants; }
    @Override public List<String> getIdDocuments()  { return idDocuments; }
}
