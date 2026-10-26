-- ============================================================================
-- V006: Inventory operations - adjustments and inter-warehouse transfers
-- ============================================================================

CREATE TABLE stock_adjustments (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    adjustment_no  VARCHAR(20)  NOT NULL,
    warehouse_id   BIGINT       NOT NULL,
    product_id     BIGINT       NOT NULL,
    quantity_delta DECIMAL(12,3) NOT NULL,
    reason         VARCHAR(30)  NOT NULL
        CONSTRAINT chk_adjustments_reason CHECK (reason IN
            ('DAMAGE','LOSS','THEFT','EXPIRY','COUNT_CORRECTION',
             'QUALITY_HOLD','FOUND','RETURN_TO_VENDOR','OTHER')),
    note           VARCHAR(300) NULL,
    created_by     BIGINT       NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_adjustments_no (adjustment_no),
    KEY ix_adjustments_stock (product_id, warehouse_id),
    CONSTRAINT fk_adjustments_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_adjustments_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_adjustments_user
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT chk_adjustments_delta CHECK (quantity_delta <> 0)
) ENGINE = InnoDB;

CREATE TABLE stock_transfers (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    transfer_no       VARCHAR(20)  NOT NULL,
    product_id        BIGINT       NOT NULL,
    from_warehouse_id BIGINT       NOT NULL,
    to_warehouse_id   BIGINT       NOT NULL,
    quantity          DECIMAL(12,3) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_transfers_status CHECK (status IN
            ('PENDING','DISPATCHED','RECEIVED','CANCELLED')),
    note              VARCHAR(300) NULL,
    version           INT          NOT NULL DEFAULT 0,
    created_by        BIGINT       NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    dispatched_by     BIGINT       NULL,
    dispatched_at     DATETIME(3)  NULL,
    received_by       BIGINT       NULL,
    received_at       DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_transfers_no (transfer_no),
    KEY ix_transfers_status (status),
    KEY ix_transfers_product (product_id),
    CONSTRAINT fk_transfers_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_from
        FOREIGN KEY (from_warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_to
        FOREIGN KEY (to_warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_transfers_created_by
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT chk_transfers_qty CHECK (quantity > 0),
    CONSTRAINT chk_transfers_route CHECK (from_warehouse_id <> to_warehouse_id)
) ENGINE = InnoDB;
