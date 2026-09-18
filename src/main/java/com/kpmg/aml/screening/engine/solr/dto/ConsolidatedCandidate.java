/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr.dto;

import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.solr.common.SolrDocument;

/**
 *
 * @author user
 */
public class ConsolidatedCandidate extends ScreeningCandidate {

    private final List<String> nameVariants;
    private final List<String> idDocuments;

    /**
     * coreName passed in to distinguish un_consolidated_core from local_watch_core
     * at audit trail level. Both cores share this class — schemas are identical.
     */
    public ConsolidatedCandidate(SolrDocument doc, String coreName) {
        super(
            (String) doc.getFieldValue("id"),
            coreName
        );
        this.nameVariants = extractNameVariants(doc);
        this.idDocuments  = extractIdDocuments(doc);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractNameVariants(SolrDocument doc) {
        List<String> variants = new ArrayList<>();

        Object nameVal = doc.getFieldValue("name_txt");

        if (nameVal instanceof List<?> nameList) {
            List<String> nameElements = nameList.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());

            // Add name_txt elements directly
            variants.addAll(nameElements);

        } else if (nameVal != null) {
            variants.add(nameVal.toString().trim());
        }

        // name_phonetic: skip

        // designation_txt
        addMultiIfPresent(variants, doc, "designation_txt");

        // pob_txt
        addMultiIfPresent(variants, doc, "pob_txt");

        return variants;
    }

    private List<String> extractIdDocuments(SolrDocument doc) {
        List<String> ids = new ArrayList<>();

        // local_watch: nic_s populated with both old+new NIC formats already paired
        //              e.g. ["792330843V","197901310017"] — no SriLankanNicUtil needed
        // un_consolidated: nic_s absent — no-op
        addMultiIfPresent(ids, doc, "nic_s");

        // document_txt: in schema, absent in real data for both cores — no-op
        addMultiIfPresent(ids, doc, "document_txt");

        addIfPresent(ids, doc, "reference_number_txt");

        return ids;
    }

    @Override public List<String> getNameVariants() { return nameVariants; }
    @Override public List<String> getIdDocuments()  { return idDocuments; }
}
