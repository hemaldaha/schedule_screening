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
import java.time.LocalDateTime;

/**
 *
 * @author user
 */
@Entity
@Table(name = "sch_screening_app_grp", schema = "aml")
public class ApprovalGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "grp_id")
    private Long grpId;

    @Column(name = "is_onboarding", nullable = false)
    private Boolean isOnboarding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sanction_id_sanction_id")
    private ScreeningScheduleEntity screeningSchedule;

    @Column(name = "creation_timestamp")
    private LocalDateTime creationTimestamp;

    @Column(name = "update_timestamp")
    private LocalDateTime updateTimestamp;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @Column(name = "deletion_token")
    private Long deletionToken;

    protected ApprovalGroupEntity() {}

    // ── Getters ───────────────────────────────────────────────────

    public Long getId()                               { return id; }
    public String getGroupName()                      { return groupName; }
    public Long getGrpId()                            { return grpId; }
    public Boolean getIsOnboarding()                  { return isOnboarding; }
    public ScreeningScheduleEntity getScreeningSchedule() { return screeningSchedule; }
    public LocalDateTime getCreationTimestamp()       { return creationTimestamp; }
    public LocalDateTime getUpdateTimestamp()         { return updateTimestamp; }
    public Long getCreatedBy()                        { return createdBy; }
    public Long getUpdatedBy()                        { return updatedBy; }
    public Boolean getDeleted()                       { return deleted; }
    public Long getDeletionToken()                    { return deletionToken; }
}
