/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 *
 * @author user
 */

@Entity
@Table(name = "sch_screening_cus", schema = "aml")
public class AlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "run_id", length = 20)
    private String runId;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "name", columnDefinition = "text")
    private String name;

    @Column(name = "nic")
    private String nic;

    @Column(name = "passport")
    private String passport;

    @Column(name = "policy_no")
    private String policyNo;

    @Column(name = "proposal_date")
    private String proposalDate;

    @Column(name = "match_count")
    private Integer matchCount;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AlertStatus status;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Column(name = "creation_timestamp", nullable = false,
            updatable = false, insertable = false)
    private LocalDateTime creationTimestamp;

    @Column(name = "update_timestamp")
    private LocalDateTime updateTimestamp;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted;

    @Column(name = "deletion_token")
    private Long deletionToken;

    @Column(name = "assigned_time_stamp")
    private LocalDateTime assignedTimeStamp;

    @Column(name = "reassignment_comment")
    private String reassignmentComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by",
                foreignKey = @ForeignKey(name = "fk_alert_created_by"))
    private ApprovalGroupEntity createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by",
                foreignKey = @ForeignKey(name = "fk_alert_updated_by"))
    private ApprovalGroupEntity updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id",
                foreignKey = @ForeignKey(name = "fk4bcdv30mtymx4jl0rarjhteat"))
    private ApprovalGroupEntity assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_assignee_id",
                foreignKey = @ForeignKey(name = "fkba25o69upjy9yws5dkdrxs700"))
    private ApprovalGroupEntity originalAssignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id",
                foreignKey = @ForeignKey(name = "fk_alert_scenario"))
    private ScreeningScheduleEntity scenario;

    public AlertEntity() {}

    public AlertEntity(Long screenId) {
        this.screenId = screenId;
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public Long getScreenId()                                        { return screenId; }
    public void setScreenId(Long screenId)                           { this.screenId = screenId; }

    public String getRunId()                                         { return runId; }
    public void setRunId(String runId)                               { this.runId = runId; }

    public String getCustomerId()                                    { return customerId; }
    public void setCustomerId(String customerId)                     { this.customerId = customerId; }

    public String getName()                                          { return name; }
    public void setName(String name)                                 { this.name = name; }

    public String getNic()                                           { return nic; }
    public void setNic(String nic)                                   { this.nic = nic; }

    public String getPassport()                                      { return passport; }
    public void setPassport(String passport)                         { this.passport = passport; }

    public String getPolicyNo()                                      { return policyNo; }
    public void setPolicyNo(String policyNo)                         { this.policyNo = policyNo; }

    public String getProposalDate()                                  { return proposalDate; }
    public void setProposalDate(String proposalDate)                 { this.proposalDate = proposalDate; }

    public Integer getMatchCount()                                   { return matchCount; }
    public void setMatchCount(Integer matchCount)                    { this.matchCount = matchCount; }

    public String getComment()                                       { return comment; }
    public void setComment(String comment)                           { this.comment = comment; }

    public AlertStatus getStatus()                                   { return status; }
    public void setStatus(AlertStatus status)                        { this.status = status; }

    public LocalDate getCreatedDate()                                { return createdDate; }
    public void setCreatedDate(LocalDate createdDate)                { this.createdDate = createdDate; }

    public LocalDateTime getCreationTimestamp()                      { return creationTimestamp; }

    public LocalDateTime getUpdateTimestamp()                        { return updateTimestamp; }
    public void setUpdateTimestamp(LocalDateTime updateTimestamp)    { this.updateTimestamp = updateTimestamp; }

    public Boolean getDeleted()                                      { return deleted; }
    public void setDeleted(Boolean deleted)                          { this.deleted = deleted; }

    public Long getDeletionToken()                                   { return deletionToken; }
    public void setDeletionToken(Long deletionToken)                 { this.deletionToken = deletionToken; }

    public LocalDateTime getAssignedTimeStamp()                      { return assignedTimeStamp; }
    public void setAssignedTimeStamp(LocalDateTime t)                { this.assignedTimeStamp = t; }

    public String getReassignmentComment()                           { return reassignmentComment; }
    public void setReassignmentComment(String reassignmentComment)   { this.reassignmentComment = reassignmentComment; }

    public ApprovalGroupEntity getCreatedBy()                        { return createdBy; }
    public void setCreatedBy(ApprovalGroupEntity createdBy)          { this.createdBy = createdBy; }

    public ApprovalGroupEntity getUpdatedBy()                        { return updatedBy; }
    public void setUpdatedBy(ApprovalGroupEntity updatedBy)          { this.updatedBy = updatedBy; }

    public ApprovalGroupEntity getAssignee()                         { return assignee; }
    public void setAssignee(ApprovalGroupEntity assignee)            { this.assignee = assignee; }

    public ApprovalGroupEntity getOriginalAssignee()                 { return originalAssignee; }
    public void setOriginalAssignee(ApprovalGroupEntity o)           { this.originalAssignee = o; }

    public ScreeningScheduleEntity getScenario()                     { return scenario; }
    public void setScenario(ScreeningScheduleEntity scenario)        { this.scenario = scenario; }
}