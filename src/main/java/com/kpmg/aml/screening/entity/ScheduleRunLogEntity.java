package com.kpmg.aml.screening.entity;

import com.kpmg.aml.screening.dto.constant.SchScreeningStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

@Entity
@Table(name = "sch_screening_schedule_run_log", schema = "aml")
public class ScheduleRunLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "creation_timestamp")
    private LocalDateTime creationTimestamp;

    @Column(name = "update_timestamp")
    private LocalDateTime updateTimestamp;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;


    @Column(name = "deleted", nullable = false)
    private Boolean deleted =false;

    @Column(name = "deletion_token", nullable = false)
    private Long deletionToken =0L;

    @Column(name = "sanction_id")
    private Long sanctionId;

    @Enumerated(EnumType.STRING)
    private SchScreeningStatus status;

    @Lob
    private String errorLog;




    public String getErrorLog() {
        return errorLog;
    }

    public void setErrorLog(String errorLog) {
        this.errorLog = errorLog;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setSanctionId(Long sanctionId) {
        this.sanctionId = sanctionId;
    }

    public void setStatus(SchScreeningStatus status) {
        this.status = status;
    }

    public Long getSanctionId() {
        return sanctionId;
    }

    public LocalDateTime getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(LocalDateTime creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public LocalDateTime getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(LocalDateTime updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }

}
