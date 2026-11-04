-- PICKUP weekly schedule and date overrides of a fulfillment config (ERD 08-fulfillment, Fulfillment §8.3, §8.5,
-- Decision N4, S). DDL copied from canonical docs/architecture/erd/08-fulfillment.md.

CREATE TABLE ful_weekly_schedule (
  id          BINARY(16) NOT NULL,
  config_id   BINARY(16) NOT NULL,
  day_of_week TINYINT    NOT NULL,
  start_time  TIME       NOT NULL,
  end_time    TIME       NOT NULL,
  max_orders  INT        NULL,                    -- capacity mỗi slot của thứ này; NULL = default của config
  max_items   INT        NULL,
  PRIMARY KEY (id),
  KEY ix_ful_weekly_schedule__config_day (config_id, day_of_week),
  CONSTRAINT fk_ful_weekly_schedule__config_id FOREIGN KEY (config_id) REFERENCES ful_branch_fulfillment_config (id),
  CONSTRAINT ck_ful_weekly_schedule__dow CHECK (day_of_week BETWEEN 1 AND 7),
  CONSTRAINT ck_ful_weekly_schedule__range CHECK (start_time < end_time)
);

-- date override > weekly schedule (§8.3)
CREATE TABLE ful_schedule_override (
  id            BINARY(16)   NOT NULL,
  config_id     BINARY(16)   NOT NULL,
  override_date DATE         NOT NULL,
  is_closed     TINYINT(1)   NOT NULL DEFAULT 0,
  start_time    TIME         NULL,
  end_time      TIME         NULL,
  max_orders    INT          NULL,                    -- override capacity cho ngày đặc biệt
  max_items     INT          NULL,
  reason        VARCHAR(255) NULL,
  created_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY ix_ful_schedule_override__config_date (config_id, override_date),
  CONSTRAINT fk_ful_schedule_override__config_id FOREIGN KEY (config_id) REFERENCES ful_branch_fulfillment_config (id),
  CONSTRAINT ck_ful_schedule_override__shape CHECK (
    (is_closed = 1 AND start_time IS NULL AND end_time IS NULL)
    OR (is_closed = 0 AND start_time IS NOT NULL AND end_time IS NOT NULL AND start_time < end_time))
);
