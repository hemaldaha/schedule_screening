/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 *
 * @author user
 */
@Entity
@Table(name = "sch_screening_san_type", schema = "aml")
public class SanctionTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "sanction_type")
    private String sanctionType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sanction_id",
                foreignKey = @ForeignKey(name = "fk4nybnqa04oqbxv2x4mqhf4gpr"))
    private ScreeningScheduleEntity screeningSchedule;

    @Column(name = "creation_timestamp")
    private LocalDateTime creationTimestamp;

    @Column(name = "update_timestamp")
    private LocalDateTime updateTimestamp;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @Column(name = "deletion_token")
    private Long deletionToken;

    protected SanctionTypeEntity() {}

    // ── Getters ───────────────────────────────────────────────────

    public Long getId()                              { return id; }
    public String getSanctionType()                  { return sanctionType; }
    public ScreeningScheduleEntity getScreeningSchedule() { return screeningSchedule; }
    public LocalDateTime getCreationTimestamp()      { return creationTimestamp; }
    public LocalDateTime getUpdateTimestamp()        { return updateTimestamp; }
    public Boolean getDeleted()                      { return deleted; }
    public Long getDeletionToken()                   { return deletionToken; }
}
