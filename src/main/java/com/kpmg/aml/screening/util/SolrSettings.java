/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 *
 * @author user
 */
@Getter
@Setter
public class SolrSettings {
    
    private String zkHosts;
    private int connectionTimeout;
    private int socketTimeout;
    private String defaultCollection;
    private Map<String, String> collections = new HashMap<>();
}
