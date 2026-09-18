/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity;

/**
 *
 * @author user
 */

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sch_screening_match", schema = "aml")
public class ScreeningMatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screen_id", nullable = false)
    private AlertEntity alert;

    @Column(name = "doc_id")
    private String docId;

    @Column(name = "list_source")
    private String listSource;

    @Column(name = "best_score")
    private Double bestScore;

    @Column(name = "above_threshold")
    private Boolean aboveThreshold;

    // TODO: add match_method when Python service is enhanced to return it
    // @Column(name = "match_method")
    // private String matchMethod;

    @Column(name = "screened_at")
    private LocalDateTime screenedAt;


    @Column(name = "alert_based_on")
    private String alertBasedOn;



    @OneToMany(mappedBy = "match", cascade = CascadeType.ALL, 
               fetch = FetchType.LAZY, orphanRemoval = true)
    private List<ScreeningMatchVariantEntity> variants = new ArrayList<>();

    public ScreeningMatchEntity() {}

    // ── Getters & Setters ─────────────────────────────────────────

    public Long getId()                              { return id; }

    public AlertEntity getAlert()                    { return alert; }
    public void setAlert(AlertEntity alert)          { this.alert = alert; }

    public String getDocId()                         { return docId; }
    public void setDocId(String docId)               { this.docId = docId; }

    public String getListSource()                    { return listSource; }
    public void setListSource(String listSource)     { this.listSource = listSource; }

    public Double getBestScore()                     { return bestScore; }
    public void setBestScore(Double bestScore)       { this.bestScore = bestScore; }

    public Boolean getAboveThreshold()               { return aboveThreshold; }
    public void setAboveThreshold(Boolean above)     { this.aboveThreshold = above; }

    public LocalDateTime getScreenedAt()             { return screenedAt; }
    public void setScreenedAt(LocalDateTime t)       { this.screenedAt = t; }

    public List<ScreeningMatchVariantEntity> getVariants() { return variants; }
    public void setVariants(List<ScreeningMatchVariantEntity> variants) { 
        this.variants = variants; 
    }

    public String getAlertBasedOn() {
        return alertBasedOn;
    }

    public void setAlertBasedOn(String alertBasedOn) {
        this.alertBasedOn = alertBasedOn;
    }
}
