/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 *
 * @author user
 */
@Entity
@Table(name = "sch_screening_match_variant", schema = "aml")
public class ScreeningMatchVariantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id", nullable = false)
    private ScreeningMatchEntity match;

    @Column(name = "variant_name")
    private String variantName;

    @Column(name = "variant_score")
    private Double variantScore;

    public ScreeningMatchVariantEntity() {}

    // ── Getters & Setters ─────────────────────────────────────────

    public Long getId()                                  { return id; }

    public ScreeningMatchEntity getMatch()               { return match; }
    public void setMatch(ScreeningMatchEntity match)     { this.match = match; }

    public String getVariantName()                       { return variantName; }
    public void setVariantName(String variantName)       { this.variantName = variantName; }

    public Double getVariantScore()                      { return variantScore; }
    public void setVariantScore(Double variantScore)     { this.variantScore = variantScore; }
}
