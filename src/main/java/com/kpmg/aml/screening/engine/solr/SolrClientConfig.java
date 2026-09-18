/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import com.kpmg.aml.screening.util.ConfigLoader;
import com.kpmg.aml.screening.util.SolrSettings;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 *
 * @author user
 */
@Lazy
@Configuration
public class SolrClientConfig {

    @Bean(destroyMethod = "close")
    public CloudSolrClient cloudSolrClient(ConfigLoader configLoader) {
        SolrSettings settings = configLoader.getSolr();

        List<String> zkHosts = Arrays.stream(settings.getZkHosts().split(","))
                .map(String::trim)
                .toList();

        return new CloudSolrClient.Builder(zkHosts, Optional.empty())
            .withZkConnectTimeout(5000, TimeUnit.MILLISECONDS)
            .withZkClientTimeout(10000, TimeUnit.MILLISECONDS)
            .build();
    }
}
