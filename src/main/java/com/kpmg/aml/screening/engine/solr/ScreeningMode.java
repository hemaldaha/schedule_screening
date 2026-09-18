/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

/**
 *
 * @author user
 */
/**
 * Screening mode determined from NameNormalizer classification.
 * Passed to SanctionStrategyBuilder.resolveStrategy() to select the
 * correct query builder method for each sanction list.
 *
 * Mode determination (inside composeStrategy per customer):
 *   NameNormalizer.Action.SCREEN_RESTRICTED  →  RESTRICTED   (single-token name)
 *   CODE_ALL_INITIALS + valid NIC present    →  NIC_ONLY     (all-initials + NIC)
 *   all other screenable names               →  NORMAL
 */
public enum ScreeningMode {

    /**
     * Standard screening — full wildcard + fuzzy + phonetic queries.
     * Applied to all names that pass NameNormalizer without anomaly.
     */
    NORMAL,

    /**
     * Restricted query mode — single-token names (NAME_SINGLE_TOKEN).
     *
     * Query strategy per list:
     *   FIU          — exact phrase + fuzzy~1 + phonetic + NIC/passport secondary
     *   FIU-ORG      — NOT_APPLICABLE (person name vs org list)
     *   consoli      — exact phrase + fuzzy~1 + phonetic on name_txt + alias_txt
     *   local_watchList — exact phrase + fuzzy~1 + phonetic + NIC secondary
     *   LXNX         — exact phrase ONLY (no fuzzy, no wildcard, no phonetic)
     */
    RESTRICTED,

    /**
     * NIC-only query mode — all-initials names where a valid Sri Lankan NIC is present.
     * Initials carry no screening value; the NIC uniquely identifies the citizen.
     *
     * Query strategy per list:
     *   FIU          — nic_no_s:(oldNic OR newNic) +/- passport_no_ss
     *   local_watchList — nic_s:(oldNic OR newNic)
     *   FIU-ORG, consoli, LXNX — NOT_APPLICABLE (returns empty list)
     */
    NIC_ONLY
}
