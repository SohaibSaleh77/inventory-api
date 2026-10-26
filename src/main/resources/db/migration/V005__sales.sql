-- ============================================================================
-- V005: Sales - sales orders, lines, reservations, shipments and returns
-- ============================================================================

CREATE TABLE sales_orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    so_no           VARCHAR(20)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_so_status CHECK (status IN
            ('DRAFT','CONFIRMED','PARTIALLY_FULFILLED','FULFILLED','CLOSED','CANCELLED')),
    order_date      DATE         NOT NULL,
    requested_date  DATE         NULL,
    subtotal        DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    discount_total  DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    tax_total       DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    total           DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    notes           VARCHAR(500) NULL,
    version         INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       NULL,
    confirmed_by    BIGINT       NULL,
    confirmed_at    DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_sales_orders_no (so_no),
    KEY ix_so_customer (customer_id),
    KEY ix_so_status (status),
    KEY ix_so_order_date (order_date),
    CONSTRAINT fk_so_customer
        FOREIGN KEY (customer_id) REFERENCES customers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_so_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_so_created_by
        FOREIGN KEY (created_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_so_confirmed_by
        FOREIGN KEY (confirmed_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE sales_order_lines (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    so_id         BIGINT       NOT NULL,
    line_no       INT          NOT NULL,
    product_id    BIGINT       NOT NULL,
    qty_ordered   DECIMAL(12,3) NOT NULL,
    qty_reserved  DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    qty_fulfilled DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    unit_price    DECIMAL(14,2) NOT NULL,
    discount_pct  DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    tax_pct       DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
    line_total    DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_so_lines (so_id, line_no),
    KEY ix_so_lines_product (product_id),
    CONSTRAINT fk_so_lines_so
        FOREIGN KEY (so_id) REFERENCES sales_orders (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_so_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_so_lines_qty CHECK (qty_ordered > 0),
    CONSTRAINT chk_so_lines_fulfil
        CHECK (qty_fulfilled >= 0 AND qty_reserved >= 0
               AND qty_fulfilled + qty_reserved <= qty_ordered),
    CONSTRAINT chk_so_lines_price CHECK (unit_price >= 0
        AND discount_pct >= 0 AND discount_pct <= 100 AND tax_pct >= 0)
) ENGINE = InnoDB;

CREATE TABLE reservations (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    sales_order_id     BIGINT       NOT NULL,
    sales_order_line_id BIGINT      NOT NULL,
    product_id         BIGINT       NOT NULL,
    warehouse_id       BIGINT       NOT NULL,
    qty_reserved       DECIMAL(12,3) NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_reservations_status
        CHECK (status IN ('ACTIVE','CONSUMED','RELEASED')),
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    released_at        DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY ix_reservations_stock (product_id, warehouse_id, status),
    KEY ix_reservations_so (sales_order_id),
    KEY ix_reservations_line (sales_order_line_id),
    CONSTRAINT fk_reservations_so
        FOREIGN KEY (sales_order_id) REFERENCES sales_orders (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_reservations_line
        FOREIGN KEY (sales_order_line_id) REFERENCES sales_order_lines (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_reservations_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reservations_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_reservations_qty CHECK (qty_reserved > 0)
) ENGINE = InnoDB;

CREATE TABLE shipments (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    shipment_no     VARCHAR(20)  NOT NULL,
    sales_order_id  BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    shipped_by      BIGINT       NULL,
    shipped_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    carrier         VARCHAR(60)  NULL,
    tracking_no     VARCHAR(80)  NULL,
    note            VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shipments_no (shipment_no),
    KEY ix_shipments_so (sales_order_id),
    CONSTRAINT fk_shipments_so
        FOREIGN KEY (sales_order_id) REFERENCES sales_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shipments_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shipments_user
        FOREIGN KEY (shipped_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE shipment_lines (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    shipment_id         BIGINT       NOT NULL,
    sales_order_line_id BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    qty_shipped         DECIMAL(12,3) NOT NULL,
    unit_cost_at_ship   DECIMAL(14,4) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_shipment_lines_shipment (shipment_id),
    KEY ix_shipment_lines_so_line (sales_order_line_id),
    CONSTRAINT fk_shipment_lines_shipment
        FOREIGN KEY (shipment_id) REFERENCES shipments (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_shipment_lines_so_line
        FOREIGN KEY (sales_order_line_id) REFERENCES sales_order_lines (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_shipment_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_shipment_lines_qty CHECK (qty_shipped > 0)
) ENGINE = InnoDB;

CREATE TABLE sales_returns (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    return_no       VARCHAR(20)  NOT NULL,
    sales_order_id  BIGINT       NOT NULL,
    warehouse_id    BIGINT       NOT NULL,
    received_by     BIGINT       NULL,
    received_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reason          VARCHAR(30)  NULL
        CONSTRAINT chk_returns_reason CHECK (reason IN
            ('DEFECTIVE','WRONG_ITEM','CUSTOMER_REMORSE','DAMAGED_IN_TRANSIT','OTHER')),
    note            VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sales_returns_no (return_no),
    KEY ix_sales_returns_so (sales_order_id),
    CONSTRAINT fk_sales_returns_so
        FOREIGN KEY (sales_order_id) REFERENCES sales_orders (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sales_returns_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_sales_returns_user
        FOREIGN KEY (received_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE sales_return_lines (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    sales_return_id     BIGINT       NOT NULL,
    sales_order_line_id BIGINT       NOT NULL,
    product_id          BIGINT       NOT NULL,
    qty_returned        DECIMAL(12,3) NOT NULL,
    restock             TINYINT(1)   NOT NULL DEFAULT 1,
    credit_amount       DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    PRIMARY KEY (id),
    KEY ix_return_lines_return (sales_return_id),
    KEY ix_return_lines_so_line (sales_order_line_id),
    CONSTRAINT fk_return_lines_return
        FOREIGN KEY (sales_return_id) REFERENCES sales_returns (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_return_lines_so_line
        FOREIGN KEY (sales_order_line_id) REFERENCES sales_order_lines (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_return_lines_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_return_lines_qty CHECK (qty_returned > 0)
) ENGINE = InnoDB;
