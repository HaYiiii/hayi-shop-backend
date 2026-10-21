-- Staff profiles and branch assignments, copied from canonical ERD 04-staff-operations.md (Decision A).
-- Only the tables effective permission needs (Decision M2); the rest of Staff Operations comes with its slice.

CREATE TABLE stf_staff_profile (
  id            BINARY(16)   NOT NULL,
  user_id       BINARY(16)   NOT NULL,                -- → iam_user (1–0..1)
  employee_code VARCHAR(32)  NOT NULL,                -- source of truth; sync sang iam_login_identifier
  full_name     VARCHAR(128) NOT NULL,
  contact_phone VARCHAR(20)  NULL,                    -- liên hệ nghiệp vụ, KHÔNG dùng để login
  status        VARCHAR(32)  NOT NULL,                -- ACTIVE | INACTIVE
  joined_on     DATE         NULL,
  left_on       DATE         NULL,
  created_at    DATETIME(6)  NOT NULL,
  updated_at    DATETIME(6)  NOT NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stf_staff_profile__user_id (user_id),
  UNIQUE KEY uk_stf_staff_profile__employee_code (employee_code)
);

CREATE TABLE stf_staff_branch_assignment (
  id               BINARY(16)  NOT NULL,
  staff_id         BINARY(16)  NOT NULL,
  branch_id        BINARY(16)  NOT NULL,              -- → brn_branch
  assignment_type  VARCHAR(32) NOT NULL,              -- PRIMARY | SUPPORTING
  status           VARCHAR(32) NOT NULL,              -- ACTIVE | ENDED
  effective_from   DATE        NOT NULL,
  effective_to     DATE        NULL,
  created_at       DATETIME(6) NOT NULL,
  updated_at       DATETIME(6) NOT NULL,
  primary_marker   BINARY(16) AS (CASE WHEN status = 'ACTIVE' AND assignment_type = 'PRIMARY' THEN staff_id END) STORED,
  active_staff_id  BINARY(16) AS (CASE WHEN status = 'ACTIVE' THEN staff_id END) STORED,
  active_branch_id BINARY(16) AS (CASE WHEN status = 'ACTIVE' THEN branch_id END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stf_staff_branch_assignment__primary_marker (primary_marker),
  UNIQUE KEY uk_stf_staff_branch_assignment__active_pair (active_staff_id, active_branch_id),
  KEY ix_stf_staff_branch_assignment__branch_status (branch_id, status),
  CONSTRAINT fk_stf_staff_branch_assignment__staff_id FOREIGN KEY (staff_id) REFERENCES stf_staff_profile (id),
  CONSTRAINT ck_stf_staff_branch_assignment__range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

