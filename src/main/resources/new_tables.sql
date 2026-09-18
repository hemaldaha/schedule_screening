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
