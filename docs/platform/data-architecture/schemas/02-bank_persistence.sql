-- DESIGN DDL — target-state wrapper for the already-applied bank-persistence Flyway.
-- Runtime truth today (public schema, H2-compatible):
--   services/bank-persistence-service/src/main/resources/db/migration/V1__init_schema.sql
-- Do not replace that file. Audit reconstruction columns are in 14-audit_event_delta.sql.

CREATE TABLE IF NOT EXISTS bank_persistence.integration_job (
    job_id              VARCHAR(36)              PRIMARY KEY,
    job_type            VARCHAR(20)              NOT NULL,
    lob                 VARCHAR(20)              NOT NULL,
    status              VARCHAR(20)              NOT NULL,
    failure_reason      VARCHAR(100),
    journey_id          VARCHAR(36),
    application_number  VARCHAR(50),
    policy_number       VARCHAR(50),
    external_req_id     VARCHAR(100),
    external_provider   VARCHAR(20)              NOT NULL DEFAULT 'ONE_SB',
    idempotency_key     VARCHAR(128)             UNIQUE,
    result_blob_id      VARCHAR(36),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    completed_at        TIMESTAMP WITH TIME ZONE,
    owned_by_instance   VARCHAR(100),
    version             BIGINT                   NOT NULL DEFAULT 0,
    created_by_actor    VARCHAR(100)             NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_job_journey     ON bank_persistence.integration_job (journey_id);
CREATE INDEX IF NOT EXISTS idx_job_status      ON bank_persistence.integration_job (status, created_at);
CREATE INDEX IF NOT EXISTS idx_job_application ON bank_persistence.integration_job (application_number);

CREATE TABLE IF NOT EXISTS bank_persistence.integration_job_offer (
    offer_id            VARCHAR(36)              PRIMARY KEY,
    job_id              VARCHAR(36)              NOT NULL REFERENCES bank_persistence.integration_job (job_id),
    insurer_code        VARCHAR(50),
    product_code        VARCHAR(100),
    product_name        VARCHAR(200),
    premium_amount      NUMERIC(15, 2),
    premium_frequency   VARCHAR(10),
    sum_assured         NUMERIC(15, 2),
    out_of_bound        BOOLEAN                  DEFAULT FALSE,
    offer_status        VARCHAR(20),
    error_summary       VARCHAR(500),
    raw_offer_blob_id   VARCHAR(36),
    funds_json          TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_offer_job ON bank_persistence.integration_job_offer (job_id);

CREATE TABLE IF NOT EXISTS bank_persistence.job_poll_attempt (
    attempt_id          BIGSERIAL                PRIMARY KEY,
    job_id              VARCHAR(36)              NOT NULL REFERENCES bank_persistence.integration_job (job_id),
    attempt_number      SMALLINT                 NOT NULL,
    attempted_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    http_status         SMALLINT,
    is_complete         BOOLEAN,
    duration_ms         INTEGER,
    error_message       VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_poll_job ON bank_persistence.job_poll_attempt (job_id, attempt_number);

CREATE TABLE IF NOT EXISTS bank_persistence.raw_payload (
    payload_id          VARCHAR(36)              PRIMARY KEY,
    job_id              VARCHAR(36)              NOT NULL,
    direction           VARCHAR(3)               NOT NULL CHECK (direction IN ('REQ', 'RES')),
    operation           VARCHAR(100)             NOT NULL,
    lob                 VARCHAR(20)              NOT NULL,
    payload_enc         BYTEA                    NOT NULL,
    encryption_key_id   VARCHAR(50)              NOT NULL,
    http_status         SMALLINT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    retain_until        DATE                     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payload_job ON bank_persistence.raw_payload (job_id, direction, created_at);

CREATE TABLE IF NOT EXISTS bank_persistence.audit_event (
    event_id            VARCHAR(36)              PRIMARY KEY,
    event_time          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    action              VARCHAR(100)             NOT NULL,
    actor_id            VARCHAR(100)             NOT NULL,
    actor_type          VARCHAR(20)              NOT NULL,
    resource_type       VARCHAR(50)              NOT NULL,
    resource_id         VARCHAR(100)             NOT NULL,
    outcome             VARCHAR(20)              NOT NULL,
    lob                 VARCHAR(20),
    journey_id          VARCHAR(36),
    distributor_id      VARCHAR(50),
    agent_id            VARCHAR(50),
    metadata            TEXT,
    trace_id            VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_audit_resource ON bank_persistence.audit_event (resource_type, resource_id, event_time);
CREATE INDEX IF NOT EXISTS idx_audit_actor    ON bank_persistence.audit_event (actor_id, event_time);
CREATE INDEX IF NOT EXISTS idx_audit_journey  ON bank_persistence.audit_event (journey_id, event_time);

-- Adapter payment-link session — not the Payment bounded-context SoR (schema payment).
CREATE TABLE IF NOT EXISTS bank_persistence.payment_session (
    session_id          VARCHAR(36)              PRIMARY KEY,
    job_id              VARCHAR(36)              REFERENCES bank_persistence.integration_job (job_id),
    application_number  VARCHAR(50)              NOT NULL,
    lob                 VARCHAR(20)              NOT NULL,
    payment_url         TEXT                     NOT NULL,
    redirect_url        TEXT                     NOT NULL,
    status              VARCHAR(30)              NOT NULL,
    external_txn_id     VARCHAR(100),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by_actor    VARCHAR(100)             NOT NULL
);
