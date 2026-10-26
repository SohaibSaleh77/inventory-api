-- ============================================================================
-- V003: Products, stock and the append-only movement ledger
-- ============================================================================

CREATE TABLE products (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    sku             VARCHAR(40)  NOT NULL,
    barcode         VARCHAR(60)  NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(2000) NULL,
    category_id     BIGINT       NULL,
    unit_of_measure VARCHAR(10)  NOT NULL DEFAULT 'EA'
        CONSTRAINT chk_products_uom
        CHECK (unit_of_measure IN ('EA','KG','G','L','ML','M','CM','BOX','PK','CTN','PLT')),
    standard_cost   DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    list_price      DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    reorder_point   DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    reorder_qty     DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    is_active       TINYINT(1)   NOT NULL DEFAULT 1,
    version         INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_products_sku (sku),
    UNIQUE KEY uq_products_barcode (barcode),
    KEY ix_products_name (name),
    KEY ix_products_category (category_id),
    KEY ix_products_active (is_active),
    CONSTRAINT fk_products_category
        FOREIGN KEY (category_id) REFERENCES categories (id)
        ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT chk_products_prices CHECK (standard_cost >= 0 AND list_price >= 0),
    CONSTRAINT chk_products_reorder CHECK (reorder_point >= 0 AND reorder_qty >= 0)
) ENGINE = InnoDB;

CREATE TABLE supplier_items (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_id     BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    supplier_sku    VARCHAR(60)  NULL,
    unit_cost       DECIMAL(14,2) NOT NULL,
    is_preferred    TINYINT(1)   NOT NULL DEFAULT 0,
    lead_time_days  INT          NOT NULL DEFAULT 7,
    version         INT          NOT NULL DEFAULT 0,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_supplier_items (supplier_id, product_id),
    KEY ix_supplier_items_product (product_id),
    KEY ix_supplier_items_sku (supplier_id, supplier_sku),
    CONSTRAINT fk_supplier_items_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_supplier_items_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT chk_supplier_items_cost CHECK (unit_cost >= 0)
) ENGINE = InnoDB;

CREATE TABLE stock_items (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    product_id       BIGINT       NOT NULL,
    warehouse_id     BIGINT       NOT NULL,
    qty_on_hand      DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    qty_reserved     DECIMAL(12,3) NOT NULL DEFAULT 0.000,
    avg_unit_cost    DECIMAL(14,4) NOT NULL DEFAULT 0.0000,
    last_counted_at  DATETIME(3)  NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_stock_items (product_id, warehouse_id),
    KEY ix_stock_items_warehouse (warehouse_id),
    CONSTRAINT fk_stock_items_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_stock_items_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_stock_on_hand CHECK (qty_on_hand >= 0),
    CONSTRAINT chk_stock_reserved CHECK (qty_reserved >= 0 AND qty_reserved <= qty_on_hand)
) ENGINE = InnoDB;

CREATE TABLE stock_movements (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    movement_no   VARCHAR(30)  NOT NULL,
    product_id    BIGINT       NOT NULL,
    warehouse_id  BIGINT       NOT NULL,
    movement_type VARCHAR(30)  NOT NULL
        CONSTRAINT chk_movements_type CHECK (movement_type IN
            ('OPENING_BALANCE','PURCHASE_RECEIPT','SALE_SHIPMENT','SALE_RETURN',
             'TRANSFER_OUT','TRANSFER_IN','ADJUSTMENT_IN','ADJUSTMENT_OUT')),
    quantity      DECIMAL(12,3) NOT NULL
        CONSTRAINT chk_movements_qty CHECK (quantity <> 0),
    unit_cost     DECIMAL(14,4) NULL,
    balance_after DECIMAL(12,3) NOT NULL,
    ref_type      VARCHAR(30)  NULL,
    ref_id        BIGINT       NULL,
    ref_no        VARCHAR(30)  NULL,
    reason        VARCHAR(30)  NULL,
    note          VARCHAR(300) NULL,
    performed_by  BIGINT       NULL,
    performed_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_movements_no (movement_no),
    KEY ix_movements_stock (product_id, warehouse_id, id),
    KEY ix_movements_type (movement_type),
    KEY ix_movements_ref (ref_type, ref_id),
    KEY ix_movements_time (performed_at),
    CONSTRAINT fk_movements_product
        FOREIGN KEY (product_id) REFERENCES products (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_movements_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_movements_performer
        FOREIGN KEY (performed_by) REFERENCES users (id)
        ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE = InnoDB;

-- The ledger is append-only: reject any UPDATE or DELETE at the database level.
-- (Single-statement trigger bodies: no BEGIN/END block, so plain JDBC and the
--  mysql CLI both parse them identically.)
CREATE TRIGGER trg_movements_no_update BEFORE UPDATE ON stock_movements
FOR EACH ROW
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'stock_movements is an append-only ledger: UPDATE is forbidden';

CREATE TRIGGER trg_movements_no_delete BEFORE DELETE ON stock_movements
FOR EACH ROW
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'stock_movements is an append-only ledger: DELETE is forbidden';
