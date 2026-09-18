/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import java.util.List;

/**
 *
 * @author user
 */
@FunctionalInterface
public interface SanctionScreeningStrategy {                 
    List<ScreeningCandidate>  execute(CustomerInfo customer);
}
