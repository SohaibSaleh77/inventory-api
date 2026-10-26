-- ============================================================================
-- V004: Purchasing - purchase orders, lines and goods receipts
-- ============================================================================

CREATE TABLE purchase_orders (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    po_no        VARCHAR(20)  NOT NULL,
    supplier_id  BIGINT       NOT NULL,
    warehouse_id BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_po_status CHECK (status IN
            ('DRAFT','SUBMITTED','APPROVED','PARTIALLY_RECEIVED','RECEIVED','CLOSED','CANCELLED')),
    order_date   DATE         NOT NULL,
    expected_date DATE        NULL,
    currency     CHAR(3)      NOT NULL DEFAULT 'USD',
    subtotal     DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    tax_total    DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    total        DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    notes        VARCHAR(500) NULL,
    version      INT          NOT NULL DEFAULT 0,
    created_by   BIGINT       NULL,
    approved_by  BIGINT       NULL,
    approved_at  DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_purchase_orders_no (po_no),
    KEY ix_po_supplier (supplier_id),
    KEY ix_po_status (status),
    KEY ix_po_order_date (order_date),
    CONSTRAINT fk_po_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_po_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_po_created_by
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_po_approved_by
        FOREIGN KEY (approved_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE purchase_order_lines (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    po_id        BIGINT       NOT NULL,
    line_no      INT          NOT NULL,
    product_id   BIGINT       NOT NULL,
    qty_ordered  DECIMAL(12,3) NOT NULL,
    qty_received DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    unit_cost    DECIMAL(14,2) NOT NULL,
    tax_pct      DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    line_total   DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_po_lines (po_id, line_no),
    KEY ix_po_lines_product (product_id),
    CONSTRAINT fk_po_lines_po
        FOREIGN KEY (po_id) REFERENCES purchase_orders (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_po_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_po_lines_qty CHECK (qty_ordered > 0),
    CONSTRAINT chk_po_lines_recv CHECK (qty_received >= 0 AND qty_received <= qty_ordered),
    CONSTRAINT chk_po_lines_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB;

CREATE TABLE receipts (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    receipt_no   VARCHAR(20)  NOT NULL,
    po_id        BIGINT       NOT NULL,
    warehouse_id BIGINT       NOT NULL,
    received_by  BIGINT       NULL,
    received_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    note         VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_receipts_no (receipt_no),
    KEY ix_receipts_po (po_id),
    CONSTRAINT fk_receipts_po
        FOREIGN KEY (po_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_receipts_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_receipts_user
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE receipt_lines (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    receipt_id   BIGINT       NOT NULL,
    po_line_id   BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    qty_accepted DECIMAL(12,3) NOT NULL,
    qty_rejected DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    unit_cost    DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_receipt_lines_receipt (receipt_id),
    KEY ix_receipt_lines_po_line (po_line_id),
    CONSTRAINT fk_receipt_lines_receipt
        FOREIGN KEY (receipt_id) REFERENCES receipts (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_receipt_lines_po_line
        FOREIGN KEY (po_line_id) REFERENCES purchase_order_lines (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_receipt_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_receipt_lines_qty CHECK (qty_accepted > 0 AND qty_rejected >= 0)
) ENGINE = InnoDB;
