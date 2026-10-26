-- ============================================================================
-- V008: Production hardening - reporting/lookup indexes + idempotency store
-- ============================================================================

-- Gross-margin reporting filters shipments by shipped_at range; without an
-- index this is a full scan on every report run.
CREATE INDEX ix_shipments_shipped_at ON shipments (shipped_at);

-- Gross margin groups by product across shipment_lines.
CREATE INDEX ix_shipment_lines_product ON shipment_lines (product_id);

-- The audit query API filters by actor_username (a login name, not an id);
-- only actor_id was indexed, so username filters were scanning.
CREATE INDEX ix_audit_actor_username ON audit_logs (actor_username);

-- Idempotency-Key store: one stored response per (user, key). Request hash
-- detects key reuse with a different payload (a client bug) -> 409 instead
-- of silently replaying an unrelated response.
CREATE TABLE idempotency_keys (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    idem_key       VARCHAR(120)  NOT NULL,
    user_id        BIGINT        NOT NULL,
    method         VARCHAR(10)   NOT NULL,
    path           VARCHAR(200)  NOT NULL,
    request_hash   CHAR(64)      NOT NULL,
    status_code    INT           NOT NULL,
    content_type   VARCHAR(100)  NULL,
    response_body  MEDIUMTEXT    NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_idempotency (user_id, idem_key),
    KEY ix_idempotency_expires (expires_at),
    CONSTRAINT fk_idempotency_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB;
