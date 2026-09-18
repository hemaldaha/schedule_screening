/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Enum.java to edit this template
 */
package com.kpmg.aml.screening.engine;

import lombok.Getter;

/**
 *
 * @author user
 */
@Getter
public enum Stage {
    ORACLE_READER   (1, "Oracle Data Extraction"),
    SOLR_SCREENING  (2, "Solr Sanction Screening"),
    PYTHON_SCORING  (3, "Python Probability Scoring"),
    ALERT_GENERATION(4, "Alert Generation");
    
    private final int order;
    private final String description;
    
    Stage(int order, String description){
        
        this.order = order;
        this.description = description;
    }
           
}
