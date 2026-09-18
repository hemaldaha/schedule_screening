/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Record.java to edit this template
 */
package com.kpmg.aml.screening.entity.dto;

/**
 *
 * @author user
 */
public record MiniAlert(Long screenId, String customerid, String policyNo) {

    @Override
    public String toString() {
        return "MiniAlert{"
                + "screenId=" + screenId
                + ", customerid='" + customerid + '\''
                + ", policyNo='" + policyNo + '\''
                + '}';
    }
}
