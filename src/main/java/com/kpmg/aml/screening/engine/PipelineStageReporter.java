/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.kpmg.aml.screening.engine;

/**
 *
 * @author user
 */
public interface PipelineStageReporter {
    void report (Stage stage, PipelineSignal signal);
}
