/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import com.kpmg.aml.screening.engine.solr.dto.ConsolidatedCandidate;
import com.kpmg.aml.screening.engine.solr.dto.FiuIndividualCandidate;
import com.kpmg.aml.screening.engine.solr.dto.FiuOrgCandidate;
import com.kpmg.aml.screening.engine.solr.dto.LxNxCandidate;
import com.kpmg.aml.screening.util.ConfigLoader;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
@Slf4j
public class SolrQueryExecutor {

    private static final int MAX_ROWS = 50;
    private final CloudSolrClient solrClient;
    private final ConfigLoader configLoader;

    public SolrQueryExecutor(CloudSolrClient solrClient, ConfigLoader configLoader) {
        this.solrClient = solrClient;
        this.configLoader = configLoader;
    }

    public List<ScreeningCandidate> executeFIU(String queryStr) {
        return query(queryStr, configLoader.getSolr().getCollections().get("individual"))
                .stream()
                .map(doc -> (ScreeningCandidate) new FiuIndividualCandidate(doc))
                .toList();
    }

    public List<ScreeningCandidate> executeFIUOrg(String queryStr) {
        return query(queryStr, configLoader.getSolr().getCollections().get("org"))
                .stream()
                .map(doc -> (ScreeningCandidate) new FiuOrgCandidate(doc))
                .toList();

    }

    public List<ScreeningCandidate> executeUN(String queryStr) {
        String coreName = configLoader.getSolr().getCollections().get("un");
        return query(queryStr, coreName)
                .stream()
                .map(doc -> (ScreeningCandidate) new ConsolidatedCandidate(doc, coreName))
                .toList();
    }

    public List<ScreeningCandidate> executeLocalWatch(String queryStr) {
        String coreName = configLoader.getSolr().getCollections().get("watch");
        return query(queryStr, coreName)
                .stream()
                .map(doc -> (ScreeningCandidate) new ConsolidatedCandidate(doc, coreName))
                .toList();
    }

    public List<ScreeningCandidate> executeLXNX(String queryStr) {
        String coreName = configLoader.getSolr().getCollections().get("lxnx");
        try {
            SolrQuery solrQuery = new SolrQuery(queryStr);
            solrQuery.setRows(MAX_ROWS);
            solrQuery.setSort("score", SolrQuery.ORDER.desc);
            solrQuery.setFields("*", "score");
            solrQuery.setParam("defType", "edismax");
            solrQuery.setParam("qf", "search_names_txt^10");
            solrQuery.setParam("pf", "search_names_txt^20");
            solrQuery.setParam("ps", "2");
            solrQuery.setParam("mm", "75%");
            solrQuery.addFilterQuery("deleted:false");
            QueryResponse response = solrClient.query(coreName, solrQuery);
            return response.getResults().stream()
                    .map(doc -> (ScreeningCandidate) new LxNxCandidate(doc, coreName))
                    .toList();
        } catch (Exception ex) {
            log.error("[SolrQueryExecutor] Failed querying core={} reason={}",
                    coreName, ex.getMessage());
            throw new SolrQueryException("Solr query failed for core: " + coreName, ex);
        }
    }

    private SolrDocumentList query(String queryStr, String coreName) {
        try {
            SolrQuery solrQuery = new SolrQuery(queryStr);
            solrQuery.setRows(MAX_ROWS);

            // -- Sorted results relavence to score descending 
            solrQuery.setSort("score", SolrQuery.ORDER.desc);

            // Request the score field, for Python scoring 
            solrQuery.setFields("*", "score");

            // Use edismax for better name matching
            solrQuery.setParam("defType", "edismax");

            // Boost exact phrase matches heavily over partial matches
//            solrQuery.setParam("qf", "name^10 aliases^5 dob^2");
            solrQuery.setParam("qf", "name_txt^10");
            solrQuery.setParam("pf", "name^20");  // phrase boost
            solrQuery.setParam("ps", "2");         // phrase slop — allows word reordering
            solrQuery.setParam("mm", "75%");       // minimum should match 75% of tokens

            QueryResponse response = solrClient.query(coreName, solrQuery);
            return response.getResults();
        } catch (Exception ex) {
            log.error("[SolrQueryExecutor] Failed querying core={} reason={}",
                    coreName, ex.getMessage());
            throw new SolrQueryException("Solr query failed for core: " + coreName, ex);
        }
    }
}
