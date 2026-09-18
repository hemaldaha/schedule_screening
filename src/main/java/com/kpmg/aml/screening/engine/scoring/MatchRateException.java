/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.scoring;

/**
 *
 * @author user
 */
public class MatchRateException extends RuntimeException {

    public MatchRateException(String message) {
        super(message);
    }

    public MatchRateException(String message, Throwable cause) {
        super(message, cause);
    }
}
