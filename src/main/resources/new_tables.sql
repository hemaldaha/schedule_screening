CREATE TABLE aml.sch_screening_match (
    id               bigserial        NOT NULL,
    screen_id        bigint           NOT NULL,
    doc_id           varchar(255),
    list_source      varchar(100),
    best_score       numeric(5,2),
    above_threshold  boolean,
    screened_at      timestamp,
    CONSTRAINT sch_screening_match_pkey PRIMARY KEY (id),
    CONSTRAINT fk_match_screen 
        FOREIGN KEY (screen_id) 
        REFERENCES aml.sch_screening_cus(screen_id)
);

CREATE TABLE aml.sch_screening_match_variant (
    id              bigserial       NOT NULL,
    match_id        bigint          NOT NULL,
    variant_name    varchar(500),
    variant_score   numeric(5,2),
    CONSTRAINT sch_screening_match_variant_pkey PRIMARY KEY (id),
    CONSTRAINT fk_variant_match 
        FOREIGN KEY (match_id) 
        REFERENCES aml.sch_screening_match(id)
);


-- Changes to the existing DDL 
-- Rename table sanction_fiu_individual
ALTER TABLE aml.sanction_fiu RENAME TO sanction_fiu_individual;

-- Rename columns
ALTER TABLE aml.sanction_fiu_individual
    RENAME COLUMN data_id TO sn;

ALTER TABLE aml.sanction_fiu_individual
    RENAME COLUMN number TO reference_number;

-- passport → passport_numbers (handles multiples as text)
ALTER TABLE aml.sanction_fiu_individual
    RENAME COLUMN passport TO passport_numbers;

ALTER TABLE aml.sanction_fiu_individual
    ALTER COLUMN passport_numbers TYPE TEXT;

-- listed_on → DATE
ALTER TABLE aml.sanction_fiu_individual
    ALTER COLUMN listed_on TYPE DATE
    USING CASE
        WHEN listed_on ~ '^\d{2}\.\d{2}\.\d{4}$' THEN TO_DATE(listed_on, 'DD.MM.YYYY')
        WHEN listed_on ~ '^\d{4}-\d{2}-\d{2}$'   THEN TO_DATE(listed_on, 'YYYY-MM-DD')
        ELSE NULL
    END;



-- sanction_fiu_og 

-- Rename column
ALTER TABLE aml.sanction_fiu_orgmav 
    RENAME COLUMN number TO reference_number;

-- listed_on → DATE
ALTER TABLE aml.sanction_fiu_org
    ALTER COLUMN listed_on TYPE DATE
    USING CASE
        WHEN listed_on ~ '^\d{2}\.\d{2}\.\d{4}$' THEN TO_DATE(listed_on, 'DD.MM.YYYY')
        WHEN listed_on ~ '^\d{4}-\d{2}-\d{2}$'   THEN TO_DATE(listed_on, 'YYYY-MM-DD')
        ELSE NULL
    END;


-- sanction_un_consolidated 
-- Rename table
ALTER TABLE aml.sanction RENAME TO sanction_un_consolidated;

-- Fix listed_on → DATE
ALTER TABLE aml.sanction_un_consolidated
    ALTER COLUMN listed_on TYPE DATE
    USING CASE
        WHEN listed_on ~ '^\d{4}-\d{2}-\d{2}$' THEN TO_DATE(listed_on, 'YYYY-MM-DD')
        ELSE NULL
    END;

-- Add missing fields
ALTER TABLE aml.sanction_un_consolidated
    ADD COLUMN IF NOT EXISTS gender           VARCHAR(20)  NULL,
    ADD COLUMN IF NOT EXISTS designation      TEXT         NULL,
    ADD COLUMN IF NOT EXISTS last_day_updated DATE         NULL,
    ADD COLUMN IF NOT EXISTS version_num      INT          NULL,
    ADD COLUMN IF NOT EXISTS aliases          TEXT         NULL,
    ADD COLUMN IF NOT EXISTS date_of_birth    TEXT         NULL,
    ADD COLUMN IF NOT EXISTS place_of_birth   TEXT         NULL,
    ADD COLUMN IF NOT EXISTS documents        TEXT         NULL;


-- nee table sanction_local_watchlist 

CREATE TABLE aml.sanction_local_watch (
    id                  BIGSERIAL       PRIMARY KEY,
    created_by          BIGINT          NULL,
    creation_timestamp  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted             BOOLEAN         NOT NULL DEFAULT FALSE,
    deletion_token      BIGINT          NULL,
    update_timestamp    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT          NULL,
    ref_no              VARCHAR(50)     NULL,
    name                TEXT            NOT NULL,
    nic_br_number       TEXT            NULL,
    passport_number     TEXT            NULL,
    blacklisted         BOOLEAN         NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_local_watch_name ON aml.sanction_local_watch (name);
CREATE INDEX idx_local_watch_nic  ON aml.sanction_local_watch (nic_br_number);


-- =============================================================================
-- AML Screening Exceptions — audit trail for transformed / skipped customers
-- Added: 2026-06-16
-- =============================================================================

CREATE TABLE IF NOT EXISTS aml.screening_exceptions (

    id                  VARCHAR(36)     NOT NULL,
    run_id              VARCHAR(20)     NOT NULL,

    client_id           VARCHAR(50)     NOT NULL,
    original_name       VARCHAR(1000)   NOT NULL,
    normalized_name     VARCHAR(1000),
    id_number           VARCHAR(50),
    entity_type         VARCHAR(20)     NOT NULL DEFAULT 'UNKNOWN',
    date_of_birth       DATE,
    nationality         CHAR(3),

    reason_code         VARCHAR(50)     NOT NULL,
    action              VARCHAR(30)     NOT NULL,
    note                TEXT,

    fiu_ind             VARCHAR(20),
    fiu_org             VARCHAR(20),
    un_consolidated     VARCHAR(20),
    local_watch         VARCHAR(20),
    lexis_nexis         VARCHAR(20),
    dow_jones           VARCHAR(20),

    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_screening_exceptions PRIMARY KEY (id),
    CONSTRAINT ck_se_entity_type       CHECK (entity_type IN ('INDIVIDUAL', 'CORPORATE', 'UNKNOWN')),
    CONSTRAINT ck_se_action            CHECK (action IN ('SKIPPED', 'TRANSFORMED_AND_SCREENED', 'SCREENED_RESTRICTED')),
    CONSTRAINT ck_se_fiu_ind           CHECK (fiu_ind IS NULL OR fiu_ind IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED')),
    CONSTRAINT ck_se_fiu_org           CHECK (fiu_org IS NULL OR fiu_org IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED')),
    CONSTRAINT ck_se_un_consolidated   CHECK (un_consolidated IS NULL OR un_consolidated IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED')),
    CONSTRAINT ck_se_local_watch       CHECK (local_watch IS NULL OR local_watch IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED')),
    CONSTRAINT ck_se_lexis_nexis       CHECK (lexis_nexis IS NULL OR lexis_nexis IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED')),
    CONSTRAINT ck_se_dow_jones         CHECK (dow_jones IS NULL OR dow_jones IN ('CANDIDATE_FOUND', 'ATTEMPTED', 'SKIPPED', 'NOT_APPLICABLE', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_se_run_id      ON aml.screening_exceptions (run_id);
CREATE INDEX IF NOT EXISTS idx_se_client_id   ON aml.screening_exceptions (client_id);
CREATE INDEX IF NOT EXISTS idx_se_action      ON aml.screening_exceptions (action);
CREATE INDEX IF NOT EXISTS idx_se_reason_code ON aml.screening_exceptions (reason_code);
CREATE INDEX IF NOT EXISTS idx_se_created_at  ON aml.screening_exceptions (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_se_any_hit     ON aml.screening_exceptions (run_id, client_id)
    WHERE fiu_ind = 'CANDIDATE_FOUND' OR fiu_org = 'CANDIDATE_FOUND'
       OR un_consolidated = 'CANDIDATE_FOUND' OR local_watch = 'CANDIDATE_FOUND'
       OR lexis_nexis = 'CANDIDATE_FOUND' OR dow_jones = 'CANDIDATE_FOUND';


-- =============================================================================
-- Task 3 / Change 1 — Add infrastructure failure context columns
-- Added: 2026-06-24
-- failure_reason: short code written when any list column = FAILED
--   (SYSTEM_TIMEOUT | PROVIDER_ERROR)
-- failure_details: raw exception message from SolrQueryException
-- Both nullable — only populated on FAILED rows. First-write-wins when
-- multiple lists fail in the same screening run.
-- =============================================================================

ALTER TABLE aml.screening_exceptions
    ADD COLUMN IF NOT EXISTS failure_reason  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS failure_details TEXT;


-- =============================================================================
-- Screening mode and execution context columns on aml.screening_exceptions
-- Added during pipeline screening mode implementation
-- screening_mode: NORMAL / RESTRICTED / NIC_ONLY — null for SKIPPED rows
-- screened_at:    timestamp when ScreeningExceptionWriter.commit() was called
-- match_count:    number of Solr candidates returned before Python scoring;
--                 null for SKIPPED customers (no Solr query executed)
-- =============================================================================

ALTER TABLE aml.screening_exceptions
    ADD COLUMN IF NOT EXISTS screening_mode  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS screened_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS match_count     INTEGER;


-- =============================================================================
-- AML Screening Runs — run-level tracking table
-- Added during pipeline run tracking implementation
-- One row per batch screening session.
-- run_id format: yyyyMMdd_HHmmss — generated in ScreeningProcessor.
-- status lifecycle: RUNNING → COMPLETED | FAILED
-- Volume counts are written by ScreeningProcessor.updateRunRecord() at completion.
-- =============================================================================

CREATE TABLE IF NOT EXISTS aml.screening_runs (
    run_id              VARCHAR(20)     NOT NULL,

    started_at          TIMESTAMP       NOT NULL,
    completed_at        TIMESTAMP,                          -- NULL while RUNNING
    duration_seconds    INTEGER,                            -- computed at completion

    status              VARCHAR(20)     NOT NULL,           -- RUNNING / COMPLETED / FAILED
    error_message       TEXT,                               -- set if status = FAILED
    triggered_by        VARCHAR(20),                        -- SCHEDULED / MANUAL
    screening_version   VARCHAR(50),

    total_customers     INTEGER,
    exception_count     INTEGER,
    alert_count         INTEGER,                            -- exception rows with ≥1 HIT column

    skipped_count       INTEGER,
    infra_failed_count  INTEGER,
    transformed_count   INTEGER,
    restricted_count    INTEGER,
    nic_only_count      INTEGER,

    CONSTRAINT pk_screening_runs PRIMARY KEY (run_id)
);

CREATE INDEX IF NOT EXISTS idx_sr_started_at ON aml.screening_runs (started_at DESC);


-- =============================================================================
-- Task 11 — Screening exceptions archive (FATF Rec. 11 — 7-year retention)
-- Added: 2026-07-04
-- Rows are moved here from aml.screening_exceptions by ArchivalService
-- after the configured retention period. Never deleted from this table.
-- LIKE ... INCLUDING ALL copies the live schema at DDL execution time —
-- run this AFTER all ALTER TABLE changes on screening_exceptions are applied.
-- =============================================================================

CREATE TABLE IF NOT EXISTS aml.screening_exceptions_archive
    (LIKE aml.screening_exceptions INCLUDING ALL);

COMMENT ON TABLE aml.screening_exceptions_archive IS
    'Long-term archive for aml.screening_exceptions rows past the retention window. '
    'Populated by ArchivalService scheduled job. Never hard-deleted.';