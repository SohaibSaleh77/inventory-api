-- ============================================================================
-- V001: Identity, security and audit infrastructure
-- ============================================================================

CREATE TABLE users (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    username               VARCHAR(50)  NOT NULL,
    email                  VARCHAR(150) NOT NULL,
    password_hash          VARCHAR(500) NOT NULL,
    full_name              VARCHAR(150) NOT NULL,
    role                   VARCHAR(20)  NOT NULL
        CONSTRAINT chk_users_role CHECK (role IN ('ADMIN','MANAGER','STAFF','VIEWER')),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','DISABLED')),
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           DATETIME(3)  NULL,
    password_changed_at    DATETIME(3)  NULL,
    last_login_at          DATETIME(3)  NULL,
    version                INT          NOT NULL DEFAULT 0,
    created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username),
    UNIQUE KEY uq_users_email (email)
) ENGINE = InnoDB;

CREATE TABLE api_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  CHAR(64)     NOT NULL,
    issued_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3)  NOT NULL,
    revoked_at  DATETIME(3)  NULL,
    user_agent  VARCHAR(255) NULL,
    ip_address  VARCHAR(45)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_api_tokens_hash (token_hash),
    KEY ix_api_tokens_user (user_id),
    KEY ix_api_tokens_expires (expires_at),
    CONSTRAINT fk_api_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE audit_logs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    actor_id       BIGINT       NULL,
    actor_username VARCHAR(50)  NOT NULL,
    action         VARCHAR(40)  NOT NULL,
    entity_type    VARCHAR(40)  NOT NULL,
    entity_id      BIGINT       NULL,
    entity_no      VARCHAR(40)  NULL,
    before_json    JSON         NULL,
    after_json     JSON         NULL,
    ip_address     VARCHAR(45)  NULL,
    detail         VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY ix_audit_entity (entity_type, entity_id),
    KEY ix_audit_time (occurred_at),
    KEY ix_audit_actor (actor_id),
    KEY ix_audit_action (action)
) ENGINE = InnoDB;

CREATE TABLE doc_sequences (
    doc_type   VARCHAR(20) NOT NULL,
    prefix     VARCHAR(8)  NOT NULL,
    next_val   BIGINT      NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                     ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (doc_type)
) ENGINE = InnoDB;
