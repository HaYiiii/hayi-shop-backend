-- Per-slot capacity counter (ERD 08-fulfillment, Fulfillment §8.5, Decision T5). S1 only reads it; claims come
-- with Create Order (S5). DDL copied from canonical docs/architecture/erd/08-fulfillment.md.

-- Counter hard-limit; tạo lazy khi claim đầu tiên. Claim = atomic conditional UPDATE.
CREATE TABLE ful_slot_capacity (
  id               BINARY(16)  NOT NULL,
  branch_id        BINARY(16)  NOT NULL,
  fulfillment_type VARCHAR(32) NOT NULL,
  slot_date        DATE        NOT NULL,
  slot_start_time  TIME        NOT NULL,
  slot_end_time    TIME        NOT NULL,
  used_orders      INT         NOT NULL DEFAULT 0,
  used_items       INT         NOT NULL DEFAULT 0,
  updated_at       DATETIME(6) NOT NULL,
  version          BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ful_slot_capacity__slot (branch_id, fulfillment_type, slot_date, slot_start_time),
  CONSTRAINT ck_ful_slot_capacity__nonneg CHECK (used_orders >= 0 AND used_items >= 0)
);
