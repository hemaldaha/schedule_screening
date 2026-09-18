/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Interface.java to edit this template
 */
package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.SanctionTypeEntity;
import com.kpmg.aml.screening.entity.dto.SanctionTypeView;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 *
 * @author user
 */
public interface SanctionTypeRepository extends JpaRepository<SanctionTypeEntity, Long> {

    @Query(value = """
    SELECT s.sanction_id_sanction_id  AS sanctionId,
           s.sanction_type            AS sanctionType
    FROM   aml.sch_screening_san_type s
    WHERE  s.deleted = false
    """, nativeQuery = true)
    List<SanctionTypeView> findActiveSanctionTypes();
}
