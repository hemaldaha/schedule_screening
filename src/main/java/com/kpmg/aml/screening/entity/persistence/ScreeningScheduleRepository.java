/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.dto.ActiveScreeningView;
import com.kpmg.aml.screening.entity.ScreeningScheduleEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 *
 * @author user
 */
public interface ScreeningScheduleRepository extends JpaRepository<ScreeningScheduleEntity, Long> {

    @Query(value = """
    SELECT s.sanction_id      AS sanctionId,
           s.frequency        AS frequency,
           s.scenario_name    AS scenarioName,
           s.query            AS query,
           s.approval_grp_id  AS approvalGrpId
    FROM   aml.sch_screening_schedule s
    WHERE  s.is_active = true
    AND    s.deleted   = false
    """, nativeQuery = true)
    List<ActiveScreeningView> findActiveSchedules();
}
