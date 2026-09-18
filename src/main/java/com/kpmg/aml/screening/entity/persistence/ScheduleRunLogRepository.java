package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.dto.constant.SchScreeningStatus;
import com.kpmg.aml.screening.entity.ScheduleRunLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRunLogRepository extends JpaRepository< ScheduleRunLogEntity ,Long > {

    Optional<ScheduleRunLogEntity> findBySanctionIdAndStatus(Long aLong, SchScreeningStatus schScreeningStatus);

    List<ScheduleRunLogEntity> findAllByStatus(SchScreeningStatus status);
}
