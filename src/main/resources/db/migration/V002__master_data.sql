-- ============================================================================
-- V002: Master data - categories, suppliers, supplier items, customers,
--       warehouses
-- ============================================================================

CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    parent_id   BIGINT       NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    is_active   TINYINT(1)   NOT NULL DEFAULT 1,
    version     INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_categories_code (code),
    UNIQUE KEY uq_categories_parent_name (parent_id, name),
    KEY ix_categories_parent (parent_id),
    CONSTRAINT fk_categories_parent
        FOREIGN KEY (parent_id) REFERENCES categories (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE suppliers (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    code               VARCHAR(20)  NOT NULL,
    name               VARCHAR(150) NOT NULL,
    contact_name       VARCHAR(120) NULL,
    email              VARCHAR(150) NULL,
    phone              VARCHAR(30)  NULL,
    address_line1      VARCHAR(200) NULL,
    address_line2      VARCHAR(200) NULL,
    city               VARCHAR(100) NULL,
    state_province     VARCHAR(100) NULL,
    postal_code        VARCHAR(20)  NULL,
    country            VARCHAR(60)  NULL,
    payment_terms_days INT          NOT NULL DEFAULT 30,
    rating             TINYINT      NULL
        CONSTRAINT chk_suppliers_rating CHECK (rating BETWEEN 1 AND 5),
    notes              VARCHAR(1000) NULL,
    is_active          TINYINT(1)   NOT NULL DEFAULT 1,
    version            INT          NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                          ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_suppliers_code (code),
    KEY ix_suppliers_name (name),
    KEY ix_suppliers_active (is_active)
) ENGINE = InnoDB;

CREATE TABLE customers (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(150) NOT NULL,
    customer_type  VARCHAR(20)  NOT NULL DEFAULT 'RETAIL'
        CONSTRAINT chk_customers_type
        CHECK (customer_type IN ('RETAIL','WHOLESALE','DISTRIBUTOR')),
    email          VARCHAR(150) NULL,
    phone          VARCHAR(30)  NULL,
    address_line1  VARCHAR(200) NULL,
    address_line2  VARCHAR(200) NULL,
    city           VARCHAR(100) NULL,
    state_province VARCHAR(100) NULL,
    postal_code    VARCHAR(20)  NULL,
    country        VARCHAR(60)  NULL,
    credit_limit   DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    notes          VARCHAR(1000) NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    version        INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                       ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_customers_code (code),
    KEY ix_customers_name (name),
    KEY ix_customers_active (is_active)
) ENGINE = InnoDB;

CREATE TABLE warehouses (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    address_line1  VARCHAR(200) NULL,
    city           VARCHAR(100) NULL,
    state_province VARCHAR(100) NULL,
    postal_code    VARCHAR(20)  NULL,
    country        VARCHAR(60)  NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1,
    version        INT          NOT NULL DEFAULT 0,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_warehouses_code (code),
    KEY ix_warehouses_name (name)
) ENGINE = InnoDB;

-- (supplier_items is created in V003, after the products table exists)
