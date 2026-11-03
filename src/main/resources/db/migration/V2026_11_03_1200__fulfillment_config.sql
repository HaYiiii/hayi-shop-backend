-- Fulfillment config per (Branch, fulfillment type) (ERD 08-fulfillment, Fulfillment §8.1–8.4, Decision R).
-- DDL copied from canonical docs/architecture/erd/08-fulfillment.md; schedules and overrides come with BE #43.

-- 1 config cho mỗi (Branch, fulfillment type)
CREATE TABLE ful_branch_fulfillment_config (
  id                          BINARY(16)   NOT NULL,
  branch_id                   BINARY(16)   NOT NULL,  -- → brn_branch
  fulfillment_type            VARCHAR(32)  NOT NULL,  -- PICKUP | DELIVERY | DINE_IN (roadmap)
  is_enabled                  TINYINT(1)   NOT NULL DEFAULT 0,
  asap_enabled                TINYINT(1)   NOT NULL DEFAULT 0,
  max_advance_days            INT          NOT NULL DEFAULT 7,
  slot_duration_minutes       INT          NOT NULL DEFAULT 15, -- 15 | 30; slot tự sinh (Decision N4)
  same_day_cutoff_minutes     INT          NOT NULL DEFAULT 180,
  min_lead_minutes            INT          NOT NULL DEFAULT 0,
  group_order_min_lead_minutes INT         NULL,      -- Group Order có thể cần lead dài hơn
  customer_cancel_cutoff_minutes INT       NULL,      -- self-cancel CONFIRMED trước cutoff (Order §7.6)
  default_slot_max_orders     INT          NULL,
  default_slot_max_items      INT          NULL,
  pickup_instructions         VARCHAR(500) NULL,
  delivery_mode               VARCHAR(32)  NULL,      -- STORE_DELIVERY | MANUAL_THIRD_PARTY_DELIVERY
  delivery_min_order_amount   BIGINT       NULL,      -- basis: Gross Product Subtotal trước discount
  created_at                  DATETIME(6)  NOT NULL,
  updated_at                  DATETIME(6)  NOT NULL,
  version                     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ful_branch_fulfillment_config__branch_type (branch_id, fulfillment_type),
  CONSTRAINT ck_ful_branch_fulfillment_config__nonneg CHECK (
    max_advance_days >= 0 AND same_day_cutoff_minutes >= 0 AND min_lead_minutes >= 0),
  CONSTRAINT ck_ful_branch_fulfillment_config__slot_duration CHECK (slot_duration_minutes IN (15, 30))
);
