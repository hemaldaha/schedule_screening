/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Record.java to edit this template
 */
package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.solr.ScreeningMode;

/**
 * Per-customer classification result produced by {@link ScreeningExceptionWriter}
 * inside {@code SanctionStrategyBuilder.composeStrategy()}.
 *
 * <ul>
 *   <li>{@code shouldScreen = false} — SKIPPED customer; exception row already
 *       persisted; strategy must not execute Solr queries.</li>
 *   <li>{@code excId = null} — NORMAL customer; no exception row written.</li>
 *   <li>{@code mode} — screening mode in effect for Solr query selection.</li>
 * </ul>
 */
public record ExceptionContext(
        boolean shouldScreen,
        String excId,
        ScreeningMode mode
) {}
