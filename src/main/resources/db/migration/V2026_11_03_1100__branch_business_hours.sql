-- Branch business hours: weekly intervals and date overrides (ERD 03-branch, Branch §4.4, Decision Q).
-- DDL copied from canonical docs/architecture/erd/03-branch.md.

-- nhiều interval / ngày
CREATE TABLE brn_business_hour (
  id          BINARY(16) NOT NULL,
  branch_id   BINARY(16) NOT NULL,
  day_of_week TINYINT    NOT NULL,                    -- 1 = Thứ Hai … 7 = Chủ nhật (ISO-8601)
  open_time   TIME       NOT NULL,
  close_time  TIME       NOT NULL,
  PRIMARY KEY (id),
  KEY ix_brn_business_hour__branch_day (branch_id, day_of_week),
  CONSTRAINT fk_brn_business_hour__branch_id FOREIGN KEY (branch_id) REFERENCES brn_branch (id),
  CONSTRAINT ck_brn_business_hour__dow CHECK (day_of_week BETWEEN 1 AND 7),
  CONSTRAINT ck_brn_business_hour__range CHECK (open_time < close_time)
);

-- một ngày override: hoặc 1 dòng is_closed = 1, hoặc ≥ 1 dòng interval
CREATE TABLE brn_business_hour_override (
  id            BINARY(16)   NOT NULL,
  branch_id     BINARY(16)   NOT NULL,
  override_date DATE         NOT NULL,
  is_closed     TINYINT(1)   NOT NULL DEFAULT 0,
  open_time     TIME         NULL,
  close_time    TIME         NULL,
  reason        VARCHAR(255) NULL,
  created_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_brn_business_hour_override__branch_date (branch_id, override_date),
  CONSTRAINT fk_brn_business_hour_override__branch_id FOREIGN KEY (branch_id) REFERENCES brn_branch (id),
  CONSTRAINT ck_brn_business_hour_override__shape CHECK (
    (is_closed = 1 AND open_time IS NULL AND close_time IS NULL)
    OR (is_closed = 0 AND open_time IS NOT NULL AND close_time IS NOT NULL AND open_time < close_time))
);
