-- ============================================================================
-- V009: Stocktaking (cycle counting) and outbound webhooks
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Physical inventory: a stocktake freezes the book quantities of one
-- warehouse as the count sheet, collects blind counts, and posts variances
-- back through the stock adjustment machinery (one adjustment + one ledger
-- movement per corrected line). The stock_items.last_counted_at column
-- finally gets a writer.
-- ---------------------------------------------------------------------------

CREATE TABLE stocktakes (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    stocktake_no  VARCHAR(30)   NOT NULL,
    warehouse_id  BIGINT        NOT NULL,
    name          VARCHAR(200)  NOT NULL,
    blind         TINYINT(1)    NOT NULL DEFAULT 0,
    status        VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_stocktakes_status CHECK
            (status IN ('DRAFT','OPEN','REVIEW','POSTED','CANCELLED')),
    note          VARCHAR(500)  NULL,
    created_by    BIGINT        NULL,
    opened_at     DATETIME(3)   NULL,
    closed_at     DATETIME(3)   NULL,
    posted_at     DATETIME(3)   NULL,
    posted_by     BIGINT        NULL,
    version       INT           NOT NULL DEFAULT 0,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stocktakes_no (stocktake_no),
    KEY ix_stocktakes_warehouse (warehouse_id),
    KEY ix_stocktakes_status (status),
    CONSTRAINT fk_stocktakes_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stocktakes_creator
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_stocktakes_poster
        FOREIGN KEY (posted_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE stocktake_lines (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    stocktake_id  BIGINT        NOT NULL,
    product_id    BIGINT        NOT NULL,
    expected_qty  DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    counted_qty   DECIMAL(12,3) NULL,
    recounts      INT           NOT NULL DEFAULT 0,
    counted_by    BIGINT        NULL,
    counted_at    DATETIME(3)   NULL,
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stocktake_lines (stocktake_id, product_id),
    KEY ix_stocktake_lines_product (product_id),
    CONSTRAINT fk_stk_lines_stocktake
        FOREIGN KEY (stocktake_id) REFERENCES stocktakes (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_stk_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stk_lines_counter
        FOREIGN KEY (counted_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT chk_stk_lines_qty CHECK
        (expected_qty >= 0 AND (counted_qty IS NULL OR counted_qty >= 0))
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Outbound webhooks (transactional outbox): business services append delivery
-- rows inside the same transaction as the change itself, and a background
-- dispatcher delivers them over HTTP with HMAC-SHA256 signatures and
-- exponential backoff. Deliveries are never deleted by the app; housekeeping
-- can prune old terminal rows.
-- ---------------------------------------------------------------------------

CREATE TABLE webhooks (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    url         VARCHAR(500) NOT NULL,
    secret      VARCHAR(100) NOT NULL,
    events      VARCHAR(500) NOT NULL,
    is_active   TINYINT(1)   NOT NULL DEFAULT 1,
    created_by  BIGINT       NULL,
    version     INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_webhooks_name (name),
    KEY ix_webhooks_active (is_active),
    CONSTRAINT fk_webhooks_creator
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE webhook_deliveries (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    webhook_id       BIGINT       NOT NULL,
    event_type       VARCHAR(40)  NOT NULL,
    -- payload is an opaque, signed byte sequence: delivered verbatim and
    -- covered by the HMAC, so it is stored as text, not a JSON column
    -- (JSON columns are re-serialized by the server, mutating the bytes)
    payload          MEDIUMTEXT   NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_wd_status CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(3)  NOT NULL,
    last_status_code INT          NULL,
    last_error       VARCHAR(500) NULL,
    delivered_at     DATETIME(3)  NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_wd_due (status, next_attempt_at),
    KEY ix_wd_webhook (webhook_id, id),
    CONSTRAINT fk_wd_webhook
        FOREIGN KEY (webhook_id) REFERENCES webhooks (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB;

-- ---------------------------------------------------------------------------
-- Reorder proposal view: active products at or below their reorder point
-- with the cheapest preferred supplier catalog entry attached. The
-- purchasing service turns these rows into draft purchase orders.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_reorder_proposals AS
SELECT
    base.product_id,
    base.sku,
    base.name,
    base.unit_of_measure,
    base.reorder_point,
    base.reorder_qty,
    base.total_available,
    CASE
        WHEN base.reorder_qty > 0 THEN base.reorder_qty
        ELSE base.reorder_point - base.total_available
    END AS suggested_qty,
    sup.supplier_id,
    sup.supplier_code,
    sup.supplier_name,
    sup.unit_cost,
    sup.lead_time_days
FROM (
    SELECT product_id, sku, name, unit_of_measure, reorder_point, reorder_qty,
           total_available
    FROM v_product_stock_summary
    WHERE reorder_point > 0 AND total_available <= reorder_point
) base
LEFT JOIN (
    SELECT ranked.*
    FROM (
        SELECT
            si.product_id,
            s.id   AS supplier_id,
            s.code AS supplier_code,
            s.name AS supplier_name,
            si.unit_cost,
            si.lead_time_days,
            ROW_NUMBER() OVER (
                PARTITION BY si.product_id
                ORDER BY si.is_preferred DESC, si.unit_cost ASC, s.code ASC
            ) AS rn
        FROM supplier_items si
        JOIN suppliers s ON s.id = si.supplier_id AND s.is_active = 1
    ) ranked
    WHERE ranked.rn = 1
) sup ON sup.product_id = base.product_id;
