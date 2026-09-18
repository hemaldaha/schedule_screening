package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.dto.constant.SchScreeningStatus;
import com.kpmg.aml.screening.entity.ScheduleRunLogEntity;
import com.kpmg.aml.screening.entity.persistence.ScheduleRunLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fixes applied:
 *  1. Added findAllByStatus() — used by ScreeningProcessor.resolveStaleRunLogs() on startup.
 *  2. updateRunLog() now stamps update_timestamp on every status change.
 */
@Service
public class ScheduleRunLog {

    private final ScheduleRunLogRepository scheduleRunLogRepository;

    public ScheduleRunLog(ScheduleRunLogRepository scheduleRunLogRepository) {
        this.scheduleRunLogRepository = scheduleRunLogRepository;
    }

    public ScheduleRunLogEntity saveRunLog(Long sanctionId, SchScreeningStatus schScreeningStatus) {
        ScheduleRunLogEntity entity = new ScheduleRunLogEntity();
        entity.setCreationTimestamp(LocalDateTime.now());
        entity.setUpdateTimestamp(LocalDateTime.now());
        entity.setSanctionId(sanctionId);
        entity.setStatus(schScreeningStatus);
        return scheduleRunLogRepository.save(entity);
    }

    public ScheduleRunLogEntity updateRunLog(ScheduleRunLogEntity entity, SchScreeningStatus schScreeningStatus) {
        entity.setStatus(schScreeningStatus);
        entity.setUpdateTimestamp(LocalDateTime.now());
        return scheduleRunLogRepository.save(entity);
    }


    public List<ScheduleRunLogEntity> findAllByStatus(SchScreeningStatus status) {
        return scheduleRunLogRepository.findAllByStatus(status);
    }
}