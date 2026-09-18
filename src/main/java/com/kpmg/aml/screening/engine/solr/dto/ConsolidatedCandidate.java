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

        // Retrieve alias_txt first — used to distinguish aliases from primary name
        // fragments in name_txt (UN consolidated only; absent in local_watch)
        List<String> aliases = new ArrayList<>();
        Object aliasVal = doc.getFieldValue("alias_txt");
        if (aliasVal instanceof List<?> aliasList) {
            aliasList.forEach(a -> aliases.add(a.toString().trim()));
        } else if (aliasVal != null) {
            aliases.add(aliasVal.toString().trim());
        }
        Set<String> aliasSet = new HashSet<>(aliases);

        // name_txt handling differs by core:
        //
        // local_watch: single clean name per element e.g. ["A.K.H.Athukorala"]
        //              no reconstruction needed, no a.k.a splitting needed
        //
        // un_consolidated: name fragments split across elements e.g. ["ERIC","BADEGE"]
        //                  AND aliases mixed in as separate elements
        //                  Reconstruct primary name by joining non-alias elements,
        //                  then add all elements individually as fallback fragments
        Object nameVal = doc.getFieldValue("name_txt");
        if (nameVal instanceof List<?> nameList) {
            List<String> nameElements = nameList.stream()
                .map(Object::toString)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

            // Add all individual name_txt elements
            variants.addAll(nameElements);

            // Reconstruct primary name: join elements that are NOT in alias_txt
            // e.g. ["FRANK KAKOLELE","BWAMBALE","FRANK KAKORERE"...] with aliases
            //      ["FRANK KAKORERE"...] → primary fragments = ["FRANK KAKOLELE","BWAMBALE"]
            //      → reconstructed = "FRANK KAKOLELE BWAMBALE"
            if (!aliasSet.isEmpty()) {
                String reconstructed = nameElements.stream()
                    .filter(n -> !aliasSet.contains(n))
                    .collect(Collectors.joining(" "))
                    .trim();
                if (!reconstructed.isBlank() && !variants.contains(reconstructed)) {
                    variants.add(reconstructed);
                }
            }
        } else if (nameVal != null) {
            variants.add(nameVal.toString().trim());
        }

        // Add aliases as discrete variants (may partially overlap name_txt elements —
        // scoring layer receives all and handles ranking; duplication is harmless)
        variants.addAll(aliases);

        // name_phonetic: exact doubled duplicate of name_txt in both cores — skip entirely

        // designation_txt: present in un_consolidated, absent in local_watch — safe no-op
        addMultiIfPresent(variants, doc, "designation_txt");

        // pob_txt: present in un_consolidated, absent in local_watch — safe no-op
        // place of birth is a weak name signal but may help with disambiguation
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
