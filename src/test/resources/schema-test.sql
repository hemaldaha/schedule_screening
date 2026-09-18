-- Minimal DDL for Testcontainers integration tests.
-- Applied once per container lifecycle via withInitScript() / @Sql.

CREATE SCHEMA IF NOT EXISTS aml;

-- ── screening_exceptions ──────────────────────────────────────────────────────
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
    screening_mode      VARCHAR(20),
    note                TEXT,
    fiu_ind             VARCHAR(20),
    fiu_org             VARCHAR(20),
    un_consolidated     VARCHAR(20),
    local_watch         VARCHAR(20),
    lexis_nexis         VARCHAR(20),
    dow_jones           VARCHAR(20),
    screened_at         TIMESTAMP,
    match_count         INTEGER,
    failure_reason      VARCHAR(50),
    failure_details     TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_screening_exceptions PRIMARY KEY (id),
    CONSTRAINT ck_se_entity_type CHECK (entity_type IN ('INDIVIDUAL', 'CORPORATE', 'UNKNOWN')),
    CONSTRAINT ck_se_action      CHECK (action IN ('SKIPPED', 'TRANSFORMED_AND_SCREENED', 'SCREENED_RESTRICTED'))
);

CREATE INDEX IF NOT EXISTS idx_se_run_id    ON aml.screening_exceptions (run_id);
CREATE INDEX IF NOT EXISTS idx_se_action    ON aml.screening_exceptions (action);
CREATE INDEX IF NOT EXISTS idx_se_created_at ON aml.screening_exceptions (created_at DESC);

-- ── screening_exceptions_archive ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS aml.screening_exceptions_archive
    (LIKE aml.screening_exceptions INCLUDING DEFAULTS INCLUDING CONSTRAINTS);

-- ── screening_runs ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS aml.screening_runs (
    run_id              VARCHAR(20)     NOT NULL,
    started_at          TIMESTAMP       NOT NULL,
    completed_at        TIMESTAMP,
    duration_seconds    INTEGER,
    status              VARCHAR(20)     NOT NULL,
    error_message       TEXT,
    triggered_by        VARCHAR(20),
    screening_version   VARCHAR(50),
    total_customers     INTEGER,
    exception_count     INTEGER,
    alert_count             INTEGER,
    normal_mode_alert_count INTEGER,
    skipped_count           INTEGER,
    infra_failed_count      INTEGER,
    transformed_count       INTEGER,
    restricted_count        INTEGER,
    nic_only_count          INTEGER,
    CONSTRAINT pk_screening_runs PRIMARY KEY (run_id)
);

-- ── sch_screening_cus (AlertEntity) ──────────────────────────────────────────
-- FK columns (created_by, updated_by, assignee_id, original_assignee_id,
-- scenario_id) kept as bare BIGINT — no FK constraints needed for JPQL tests.
CREATE TABLE IF NOT EXISTS aml.sch_screening_cus (
    screen_id               BIGSERIAL       NOT NULL,
    customer_id             VARCHAR(255),
    name                    TEXT,
    nic                     VARCHAR(255),
    passport                VARCHAR(255),
    policy_no               VARCHAR(255),
    proposal_date           VARCHAR(255),
    match_count             INTEGER,
    comment                 TEXT,
    status                  VARCHAR(50),
    created_date            DATE,
    creation_timestamp      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_timestamp        TIMESTAMP,
    deleted                 BOOLEAN         NOT NULL DEFAULT FALSE,
    deletion_token          BIGINT,
    assigned_time_stamp     TIMESTAMP,
    reassignment_comment    VARCHAR(255),
    created_by              BIGINT,
    updated_by              BIGINT,
    assignee_id             BIGINT,
    original_assignee_id    BIGINT,
    run_id                  VARCHAR(20),
    scenario_id             BIGINT,
    CONSTRAINT sch_screening_cus_pkey PRIMARY KEY (screen_id)
);
