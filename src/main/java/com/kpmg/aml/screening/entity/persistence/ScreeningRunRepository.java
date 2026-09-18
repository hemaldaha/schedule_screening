package com.kpmg.aml.screening.entity.persistence;

import com.kpmg.aml.screening.entity.ScreeningRunEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for aml.screening_runs.
 */
public interface ScreeningRunRepository extends JpaRepository<ScreeningRunEntity, String> {

    /** Returns all runs ordered most-recent first — used by GET /api/screening/exceptions/runs. */
    List<ScreeningRunEntity> findAllByOrderByStartedAtDesc();
}
