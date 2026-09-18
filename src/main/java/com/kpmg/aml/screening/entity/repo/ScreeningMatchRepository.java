/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.entity.repo;

import com.kpmg.aml.screening.entity.ScreeningMatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 *
 * @author user
 */
public interface ScreeningMatchRepository extends JpaRepository<ScreeningMatchEntity, Long> {
    
}
