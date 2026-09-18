/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr.dto;

import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.SolrDocument;

/**
 *
 * @author user
 */

public class LxNxCandidate extends ScreeningCandidate {
    
    private final List<String> nameVariants;
    private final List<String> idDocuments;
    
    public LxNxCandidate(SolrDocument doc, String coreName){
        super((String)doc.getFieldValue("id"), coreName);
         this.nameVariants = extractNameVariants(doc);
        this.idDocuments  = extractIdDocuments(doc);
    }
    
    private List<String> extractNameVariants(SolrDocument doc) {
        List<String> variants = new ArrayList<>();
        addMultiIfPresent(variants, doc, "search_names_txt");
        return variants;
    }

    private List<String> extractIdDocuments(SolrDocument doc) {
        List<String> ids = new ArrayList<>();
        addMultiIfPresent(ids, doc, "id_docs_ss");
        return ids;
    }
    
    @Override public List<String> getNameVariants() { return nameVariants; }
    @Override public List<String> getIdDocuments()  { return idDocuments; }
}
