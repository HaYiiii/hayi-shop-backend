-- Identity & Access schema, copied from canonical ERD 01-identity-access.md (Identity §2.12, Decision A, D).

CREATE TABLE iam_user (
  id                 BINARY(16)   NOT NULL,
  status             VARCHAR(32)  NOT NULL,           -- ACTIVE | DISABLED | CLOSED
  locked_until       DATETIME(6)  NULL,
  lock_reason        VARCHAR(64)  NULL,
  failed_login_count INT          NOT NULL DEFAULT 0,
  last_login_at      DATETIME(6)  NULL,
  closed_at          DATETIME(6)  NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  version            BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);

CREATE TABLE iam_login_identifier (
  id               BINARY(16)   NOT NULL,
  user_id          BINARY(16)   NOT NULL,
  identifier_type  VARCHAR(32)  NOT NULL,             -- PHONE | EMAIL | EMPLOYEE_CODE
  normalized_value VARCHAR(255) NOT NULL,             -- PHONE: E.164; EMAIL: lower-case
  verified_at      DATETIME(6)  NULL,
  created_at       DATETIME(6)  NOT NULL,
  updated_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_login_identifier__type_value (identifier_type, normalized_value),
  UNIQUE KEY uk_iam_login_identifier__user_type (user_id, identifier_type),
  CONSTRAINT fk_iam_login_identifier__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

CREATE TABLE iam_user_credential (
  user_id              BINARY(16)   NOT NULL,
  password_hash        VARCHAR(255) NOT NULL,         -- Argon2id/BCrypt, không plaintext
  must_change_password TINYINT(1)   NOT NULL DEFAULT 0,
  password_changed_at  DATETIME(6)  NOT NULL,
  updated_at           DATETIME(6)  NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_iam_user_credential__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

CREATE TABLE iam_mfa_factor (
  id               BINARY(16)    NOT NULL,
  user_id          BINARY(16)    NOT NULL,
  factor_type      VARCHAR(32)   NOT NULL,            -- TOTP
  secret_encrypted VARBINARY(512) NOT NULL,           -- mã hóa bằng app key, không plaintext
  status           VARCHAR(32)   NOT NULL,            -- PENDING | ACTIVE | DISABLED
  activated_at     DATETIME(6)   NULL,
  last_used_at     DATETIME(6)   NULL,
  created_at       DATETIME(6)   NOT NULL,
  updated_at       DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  KEY ix_iam_mfa_factor__user_id (user_id),
  CONSTRAINT fk_iam_mfa_factor__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

CREATE TABLE iam_mfa_recovery_code (
  id         BINARY(16)  NOT NULL,
  user_id    BINARY(16)  NOT NULL,
  code_hash  CHAR(64)    NOT NULL,
  used_at    DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY ix_iam_mfa_recovery_code__user_id (user_id),
  CONSTRAINT fk_iam_mfa_recovery_code__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

-- 1 session = 1 refresh-token family
CREATE TABLE iam_user_session (
  id                  BINARY(16)   NOT NULL,
  user_id             BINARY(16)   NOT NULL,
  persona             VARCHAR(32)  NOT NULL,          -- CUSTOMER | STAFF
  channel             VARCHAR(32)  NOT NULL,          -- CUSTOMER_WEB | BACKOFFICE_WEB
  device_label        VARCHAR(128) NULL,
  user_agent          VARCHAR(512) NULL,
  ip_address          VARCHAR(45)  NULL,
  step_up_verified_at DATETIME(6)  NULL,
  created_at          DATETIME(6)  NOT NULL,
  last_active_at      DATETIME(6)  NOT NULL,
  expires_at          DATETIME(6)  NOT NULL,          -- absolute session lifetime
  revoked_at          DATETIME(6)  NULL,
  revoke_reason       VARCHAR(64)  NULL,              -- LOGOUT | REUSE_DETECTED | OFFBOARDED | ADMIN | PASSWORD_CHANGED ...
  version             BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY ix_iam_user_session__user_persona_revoked (user_id, persona, revoked_at),
  CONSTRAINT fk_iam_user_session__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

CREATE TABLE iam_refresh_token (
  id             BINARY(16)  NOT NULL,
  session_id     BINARY(16)  NOT NULL,
  token_hash     CHAR(64)    NOT NULL,                -- SHA-256
  issued_at      DATETIME(6) NOT NULL,
  expires_at     DATETIME(6) NOT NULL,
  rotated_at     DATETIME(6) NULL,                    -- != NULL mà bị dùng lại ⇒ reuse ⇒ revoke session
  replaced_by_id BINARY(16)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_refresh_token__token_hash (token_hash),
  KEY ix_iam_refresh_token__session_id (session_id),
  CONSTRAINT fk_iam_refresh_token__session_id FOREIGN KEY (session_id) REFERENCES iam_user_session (id),
  CONSTRAINT fk_iam_refresh_token__replaced_by_id FOREIGN KEY (replaced_by_id) REFERENCES iam_refresh_token (id)
);

-- id của challenge VERIFIED cũng là "verification proof" (Identity §2.13)
CREATE TABLE iam_verification_challenge (
  id            BINARY(16)   NOT NULL,
  purpose       VARCHAR(32)  NOT NULL,                -- REGISTRATION | PASSWORD_RECOVERY | PHONE_CHANGE | RISK_VERIFICATION | STEP_UP
  target        VARCHAR(255) NOT NULL,                -- normalized phone/email
  user_id       BINARY(16)   NULL,
  channel       VARCHAR(32)  NOT NULL,                -- SMS | EMAIL | ZALO | TOTP
  code_hash     CHAR(64)     NULL,                    -- NULL khi dùng TOTP
  status        VARCHAR(32)  NOT NULL,                -- PENDING | VERIFIED | CONSUMED | EXPIRED | FAILED
  attempt_count INT          NOT NULL DEFAULT 0,
  max_attempts  INT          NOT NULL,
  expires_at    DATETIME(6)  NOT NULL,
  verified_at   DATETIME(6)  NULL,
  consumed_at   DATETIME(6)  NULL,
  created_at    DATETIME(6)  NOT NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY ix_iam_verification_challenge__target_purpose (target, purpose, created_at),
  KEY ix_iam_verification_challenge__user_id (user_id),
  CONSTRAINT fk_iam_verification_challenge__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id)
);

CREATE TABLE iam_role (
  id            BINARY(16)   NOT NULL,
  code          VARCHAR(64)  NOT NULL,                -- OWNER | ADMIN | MANAGER | STAFF | BARISTA
  name          VARCHAR(128) NOT NULL,
  allowed_scope VARCHAR(32)  NOT NULL,                -- STORE | BRANCH
  is_system     TINYINT(1)   NOT NULL DEFAULT 1,
  requires_mfa  TINYINT(1)   NOT NULL DEFAULT 0,      -- 1 cho mọi role STORE (Decision D4)
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_role__code (code)
);

CREATE TABLE iam_permission (
  id          BINARY(16)   NOT NULL,
  code        VARCHAR(128) NOT NULL,                  -- ví dụ ORDER_CONFIRM, PAYMENT_VERIFY, REFUND_APPROVE
  description VARCHAR(255) NULL,
  created_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_permission__code (code)
);

CREATE TABLE iam_role_permission (
  role_id       BINARY(16) NOT NULL,
  permission_id BINARY(16) NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_iam_role_permission__role_id FOREIGN KEY (role_id) REFERENCES iam_role (id),
  CONSTRAINT fk_iam_role_permission__permission_id FOREIGN KEY (permission_id) REFERENCES iam_permission (id)
);

-- STORE scope ngầm định toàn hệ thống: không có store_id (Decision D2)
CREATE TABLE iam_user_store_role (
  id                 BINARY(16)  NOT NULL,
  user_id            BINARY(16)  NOT NULL,
  role_id            BINARY(16)  NOT NULL,
  granted_at         DATETIME(6) NOT NULL,
  granted_by_user_id BINARY(16)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_user_store_role__user_role (user_id, role_id),
  CONSTRAINT fk_iam_user_store_role__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id),
  CONSTRAINT fk_iam_user_store_role__role_id FOREIGN KEY (role_id) REFERENCES iam_role (id)
);

CREATE TABLE iam_user_branch_role (
  id                 BINARY(16)  NOT NULL,
  user_id            BINARY(16)  NOT NULL,
  branch_id          BINARY(16)  NOT NULL,            -- → brn_branch (cross-module, không FK)
  role_id            BINARY(16)  NOT NULL,
  granted_at         DATETIME(6) NOT NULL,
  granted_by_user_id BINARY(16)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_iam_user_branch_role__user_branch_role (user_id, branch_id, role_id),
  KEY ix_iam_user_branch_role__branch_id (branch_id),
  CONSTRAINT fk_iam_user_branch_role__user_id FOREIGN KEY (user_id) REFERENCES iam_user (id),
  CONSTRAINT fk_iam_user_branch_role__role_id FOREIGN KEY (role_id) REFERENCES iam_role (id)
);

CREATE TABLE iam_security_event (
  id                BINARY(16)   NOT NULL,
  event_type        VARCHAR(64)  NOT NULL,            -- LOGIN_SUCCESS | LOGIN_FAILED | ... (Identity §2.9)
  user_id           BINARY(16)   NULL,
  session_id        BINARY(16)   NULL,
  identifier_masked VARCHAR(255) NULL,                -- ví dụ +8490****123 khi login fail chưa biết user
  ip_address        VARCHAR(45)  NULL,
  user_agent        VARCHAR(512) NULL,
  metadata          JSON         NULL,                -- đã redact
  trace_id          VARCHAR(64)  NULL,
  occurred_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_iam_security_event__user_occurred (user_id, occurred_at),
  KEY ix_iam_security_event__type_occurred (event_type, occurred_at)
);
