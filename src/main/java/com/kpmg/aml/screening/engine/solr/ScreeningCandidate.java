/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import java.util.List;
import org.apache.solr.common.SolrDocument;

/**
 *
 * @author user
 */
public abstract class ScreeningCandidate {

    private final String docId;
    private final String coreName;

    protected ScreeningCandidate(String docId, String coreName) {
        this.docId = docId;
        this.coreName = coreName;
    }

    // -- Core identity - audit trail 
    public String getDocId() {
        return docId;
    }

    public String getCoreName() {
        return coreName;
    }

    // -- Scoring contracts -- to be implemented by sub-classes
    public abstract List<String> getNameVariants();

    public abstract List<String> getIdDocuments();

    // -- Solr field extraction utilities 
    protected void addIfPresent(List<String> target, SolrDocument doc, String field) {
        Object val = doc.getFieldValue(field);
        if (val != null) {
            target.add(val.toString());
        }
    }

    protected void addMultiIfPresent(List<String> target, SolrDocument doc, String field) {
        Object val = doc.getFieldValue(field);
        if (val instanceof List) {
            target.addAll((List<String>) val);
        } else if (val != null) {
            target.add(val.toString());
        }

    }
}
