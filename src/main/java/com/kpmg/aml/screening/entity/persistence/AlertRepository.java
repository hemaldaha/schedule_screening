/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.dto.MiniAlert;
import com.kpmg.aml.screening.entity.AlertEntity;
import com.kpmg.aml.screening.entity.AlertStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 *
 * @author user
 */
public interface AlertRepository extends JpaRepository<AlertEntity, Long> {

    @Query("""
    SELECT DISTINCT new com.kpmg.aml.screening.entity.dto.MiniAlert(
        a.screenId,
        a.customerId,
        a.policyNo
    )
    FROM AlertEntity a
    WHERE a.status IN (:statuses)
    AND a.deleted = false
    ORDER BY a.customerId ASC
    """)
    List<MiniAlert> findCustomersToOmit(
            @Param("statuses") List<AlertStatus> statuses
    );
    
    
}
