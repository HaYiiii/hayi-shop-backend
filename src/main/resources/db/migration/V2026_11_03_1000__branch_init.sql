-- Branch, copied from canonical ERD 03-branch.md (Branch §4.1–4.3). Business hours come with BE #41.

CREATE TABLE brn_branch (
  id            BINARY(16)   NOT NULL,
  branch_code   VARCHAR(32)  NOT NULL,                -- human-readable, không tái sử dụng
  name          VARCHAR(128) NOT NULL,
  status        VARCHAR(32)  NOT NULL,                -- DRAFT | ACTIVE | INACTIVE | CLOSED
  phone         VARCHAR(20)  NULL,
  email         VARCHAR(255) NULL,
  address_line  VARCHAR(255) NOT NULL,
  ward_name     VARCHAR(128) NULL,
  district_name VARCHAR(128) NULL,
  province_name VARCHAR(128) NOT NULL,
  latitude      DECIMAL(9,6) NULL,
  longitude     DECIMAL(9,6) NULL,
  timezone      VARCHAR(64)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  activated_at  DATETIME(6)  NULL,
  closed_at     DATETIME(6)  NULL,
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  NOT NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_brn_branch__branch_code (branch_code)
);
