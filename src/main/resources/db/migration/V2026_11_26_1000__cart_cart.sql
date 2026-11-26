-- Cart & Pricing: the server Cart of a signed-in Customer per Branch (Cart & Pricing §6.2–§6.4, Decision AV).
-- DDL from ERD 06-cart.
CREATE TABLE crt_cart (
  id             BINARY(16)  NOT NULL,
  customer_id    BINARY(16)  NOT NULL,                -- → cus_customer_profile
  branch_id      BINARY(16)  NOT NULL,                -- immutable
  status         VARCHAR(32) NOT NULL,                -- ACTIVE | CONVERTED | DISCARDED
  coupon_code    VARCHAR(64) NULL,                    -- tối đa 1 coupon
  converted_order_id BINARY(16) NULL,                 -- → ord_order (finalize idempotent)
  created_at     DATETIME(6) NOT NULL,
  updated_at     DATETIME(6) NOT NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  active_customer_id BINARY(16) AS (CASE WHEN status = 'ACTIVE' THEN customer_id END) STORED,
  active_branch_id   BINARY(16) AS (CASE WHEN status = 'ACTIVE' THEN branch_id END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crt_cart__active_customer_branch (active_customer_id, active_branch_id),
  KEY ix_crt_cart__customer_id (customer_id)
);

CREATE TABLE crt_cart_item (
  id                    BINARY(16)   NOT NULL,
  cart_id               BINARY(16)   NOT NULL,
  product_id            BINARY(16)   NOT NULL,        -- → cat_product
  variant_id            BINARY(16)   NOT NULL,        -- → cat_product_variant
  quantity              INT          NOT NULL,
  note_normalized       VARCHAR(255) NULL,
  equivalence_key       CHAR(64)     NOT NULL,        -- SHA-256(product|variant|sorted options|normalized note)
  last_seen_unit_price  BIGINT       NULL,            -- provisional, chỉ để phát hiện thay đổi giá
  issue_code            VARCHAR(64)  NULL,            -- ví dụ STALE_SELECTION, UNAVAILABLE (merge giữ intent nhưng flag)
  created_at            DATETIME(6)  NOT NULL,
  updated_at            DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crt_cart_item__cart_equivalence (cart_id, equivalence_key),
  CONSTRAINT fk_crt_cart_item__cart_id FOREIGN KEY (cart_id) REFERENCES crt_cart (id),
  CONSTRAINT ck_crt_cart_item__quantity CHECK (quantity > 0)
);

CREATE TABLE crt_cart_item_option (
  cart_item_id    BINARY(16) NOT NULL,
  option_item_id  BINARY(16) NOT NULL,                -- → cat_option_item
  option_group_id BINARY(16) NOT NULL,                -- → cat_option_group
  PRIMARY KEY (cart_item_id, option_item_id),
  CONSTRAINT fk_crt_cart_item_option__cart_item_id FOREIGN KEY (cart_item_id) REFERENCES crt_cart_item (id)
);
