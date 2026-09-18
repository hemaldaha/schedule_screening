/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 *
 * @author user
 */
@Entity
@Table(name = "sch_screening_schedule", schema = "aml")
public class ScreeningScheduleEntity {

    @Id
    @Column(name = "sanction_id", nullable = false)
    private Long sanctionId;

    @Column(name = "scenario_name")
    private String scenarioName;

    @Column(name = "description")
    private String description;

    @Column(name = "frequency")
    private String frequency;

    @Column(name = "query", columnDefinition = "text")
    private String query;

    @Column(name = "approval_grp_id")
    private String approvalGrpId;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Column(name = "updated_date")
    private LocalDate updatedDate;

    @Column(name = "created_user")
    private String createdUser;

    @Column(name = "lastupdated_user_id")
    private Long lastUpdatedUserId;

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

    protected ScreeningScheduleEntity() {}

    // ── Getters ───────────────────────────────────────────────────

    public Long getSanctionId()                { return sanctionId; }
    public String getScenarioName()            { return scenarioName; }
    public String getDescription()             { return description; }
    public String getFrequency()               { return frequency; }
    public String getQuery()                   { return query; }
    public String getApprovalGrpId()           { return approvalGrpId; }
    public Boolean getIsActive()               { return isActive; }
    public LocalDate getCreatedDate()          { return createdDate; }
    public LocalDate getUpdatedDate()          { return updatedDate; }
    public String getCreatedUser()             { return createdUser; }
    public Long getLastUpdatedUserId()         { return lastUpdatedUserId; }
    public LocalDateTime getCreationTimestamp(){ return creationTimestamp; }
    public LocalDateTime getUpdateTimestamp()  { return updateTimestamp; }
    public Long getCreatedBy()                 { return createdBy; }
    public Long getUpdatedBy()                 { return updatedBy; }
    public Boolean getDeleted()                { return deleted; }
    public Long getDeletionToken()             { return deletionToken; }
}
