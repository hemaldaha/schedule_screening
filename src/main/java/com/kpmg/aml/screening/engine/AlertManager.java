/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.entity.dto.MiniAlert;
import com.kpmg.aml.screening.entity.AlertStatus;
import com.kpmg.aml.screening.entity.persistence.AlertRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 *
 * @author user
 */
@Component
@Scope("singleton")
@Slf4j
public class AlertManager {

    private Map<String, MiniAlert> alertsByCustomerId; // -- <clientId, MiniAlert>
    private Map<String, String> alertsByPolicyNo; // -- <policyNo, clientId>

    private final AlertRepository alertRepo;

    public AlertManager(AlertRepository alertRepo) {
        this.alertRepo = alertRepo;
    }

    @PostConstruct
    public void init() {

        alertsByCustomerId = new ConcurrentHashMap<>(1000, 0.9f, 4);
        alertsByPolicyNo = new ConcurrentHashMap<>(1000, 0.9f, 4);
    }

    public void loadData() {

        List<MiniAlert> alertList;
        alertList = alertRepo.findCustomersToOmit(
                List.of(AlertStatus.unassigned, AlertStatus.pending)
        );

        alertsByCustomerId
                = alertList.stream()
                        .filter(alert -> alert.customerid() != null)
                        .collect(Collectors.toMap(
                                MiniAlert::customerid,
                                alert -> alert,
                                (existing, replacement) -> existing
                        ));
        alertsByPolicyNo
                = alertList.stream()
                        .filter(alert -> alert.policyNo() != null)
                        .collect(Collectors.toMap(
                                MiniAlert::policyNo, MiniAlert::customerid,
                                (existing, replacement) -> existing));

        log.info("SYSTEM INFO: Global alert lists populated. Nos Elements:{}", alertsByCustomerId.size());
    }

    public boolean isExist(String customerId, String policyNo) {
        return alertsByCustomerId.get(customerId) == null
                ? alertsByPolicyNo.get(policyNo) != null : true;
    }
}
