/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 *
 * @author user
 */
public interface AlertEntityRepository extends JpaRepository<AlertEntity, Long>{
    
}
