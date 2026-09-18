/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Enum.java to edit this template
 */
package com.kpmg.aml.screening.engine;

/**
 *
 * @author user
 */
public enum PipelineSignal {
    RUNNING, 
    EXHAUSTED, // -- Oracle source of truth 
    DRAINING, // -- downstream stages, pipeline slowing down 
    DONE, // -- stage complete
    ERROR // -- Runtime error, Pipeline halt
}
