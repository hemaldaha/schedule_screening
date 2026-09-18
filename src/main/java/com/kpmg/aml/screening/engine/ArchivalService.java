package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.util.ConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that moves expired rows from {@code aml.screening_exceptions}
 * to {@code aml.screening_exceptions_archive} once they exceed the configured
 * retention period (default 7 years — FATF Recommendation 11).
 *
 * <p>The archive is append-only. Rows are never deleted from the archive table.
 *
 * <p>The move is a single atomic CTE — either all qualifying rows are archived
 * or none (no partial writes on failure).
 *
 * <p>Schedule is configurable via {@code application.config.screening.archival-cron}.
 * Default: {@code "0 0 2 1 * *"} — 02:00 on the 1st of every month.
 */
@Component
@Slf4j
public class ArchivalService {

    private final JdbcTemplate jdbcTemplate;  // @Primary (PostgreSQL) — no qualifier needed
    private final ConfigLoader configLoader;

    public ArchivalService(JdbcTemplate jdbcTemplate, ConfigLoader configLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.configLoader = configLoader;
    }

    @Scheduled(cron = "${application.config.screening.archival-cron:0 0 2 1 * *}")
    public void archiveOldExceptions() {
        int years = configLoader.getScreening().getRetentionYears();
        log.info("[ArchivalService] Starting archival — retentionYears={}", years);

        int archived = jdbcTemplate.update("""
                WITH moved AS (
                    DELETE FROM aml.screening_exceptions
                    WHERE created_at < NOW() - (? * INTERVAL '1 year')
                    RETURNING *
                )
                INSERT INTO aml.screening_exceptions_archive
                SELECT * FROM moved
                """, years);

        log.info("[ArchivalService] Archival complete — rows moved={}", archived);
    }
}
