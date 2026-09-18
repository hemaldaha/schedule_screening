/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Record.java to edit this template
 */
package com.kpmg.aml.screening.entity.dto;

/**
 *
 * @author user
 */
public record CustomerInfo(
        String clientId, // maps to client_id
        String name, // maps to name
        String nic, // maps to nic
        String passport, // maps to passport
        String dob, // maps to dob
        String proposalDate,// maps to proposal_date
        String policyNo // maps to policy_no
        ) {

}
