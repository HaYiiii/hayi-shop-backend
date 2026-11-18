-- Catalog: what a Branch sells and at which Branch price (Catalog §5.5, §5.6, Decision F, W2, W3, AD).
-- DDL from ERD 05-catalog (held_state added by Decision AD5).

-- Aggregate riêng theo (branch, product): sellability + giá Branch (§5.5, §5.6)
CREATE TABLE cat_branch_product_listing (
  id                       BINARY(16)   NOT NULL,
  branch_id                BINARY(16)   NOT NULL,     -- → brn_branch
  product_id               BINARY(16)   NOT NULL,
  availability_state       VARCHAR(32)  NOT NULL,     -- NOT_AVAILABLE | AVAILABLE | SOLD_OUT | PAUSED
  held_state               VARCHAR(32)  NULL,         -- SOLD_OUT | PAUSED giữ lại khi gỡ bán (Decision AD5)
  sold_out_until           DATETIME(6)  NULL,         -- SOLD_OUT_UNTIL
  state_reason             VARCHAR(255) NULL,
  stock_shortage_since     DATETIME(6)  NULL,         -- tín hiệu Inventory (AUTO_SOLD_OUT); restock chỉ clear cột này
  state_changed_at         DATETIME(6)  NULL,
  state_changed_by_user_id BINARY(16)   NULL,
  created_at               DATETIME(6)  NOT NULL,
  updated_at               DATETIME(6)  NOT NULL,
  version                  BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_branch_product_listing__branch_product (branch_id, product_id),
  KEY ix_cat_branch_product_listing__branch_state (branch_id, availability_state),
  KEY ix_cat_branch_product_listing__sold_out_until (sold_out_until),
  CONSTRAINT fk_cat_branch_product_listing__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id)
);

CREATE TABLE cat_branch_variant_listing (
  listing_id               BINARY(16)   NOT NULL,
  variant_id               BINARY(16)   NOT NULL,
  price                    BIGINT       NULL,         -- NULL = dùng base_price
  availability_state       VARCHAR(32)  NOT NULL DEFAULT 'AVAILABLE', -- NOT_AVAILABLE | AVAILABLE | SOLD_OUT | PAUSED
  held_state               VARCHAR(32)  NULL,         -- SOLD_OUT | PAUSED giữ lại khi gỡ bán (Decision AD5)
  sold_out_until           DATETIME(6)  NULL,
  state_reason             VARCHAR(255) NULL,
  stock_shortage_since     DATETIME(6)  NULL,
  state_changed_at         DATETIME(6)  NULL,
  state_changed_by_user_id BINARY(16)   NULL,
  updated_at               DATETIME(6)  NOT NULL,
  PRIMARY KEY (listing_id, variant_id),
  KEY ix_cat_branch_variant_listing__sold_out_until (sold_out_until),
  CONSTRAINT fk_cat_branch_variant_listing__listing_id FOREIGN KEY (listing_id) REFERENCES cat_branch_product_listing (id),
  CONSTRAINT fk_cat_branch_variant_listing__variant_id FOREIGN KEY (variant_id) REFERENCES cat_product_variant (id),
  CONSTRAINT ck_cat_branch_variant_listing__price CHECK (price IS NULL OR price >= 0)
);

CREATE TABLE cat_branch_option_item_listing (
  branch_id                BINARY(16)   NOT NULL,     -- → brn_branch
  option_item_id           BINARY(16)   NOT NULL,
  extra_price              BIGINT       NULL,         -- NULL = dùng Product override / library default
  availability_state       VARCHAR(32)  NOT NULL DEFAULT 'AVAILABLE',
  sold_out_until           DATETIME(6)  NULL,
  state_reason             VARCHAR(255) NULL,
  stock_shortage_since     DATETIME(6)  NULL,
  state_changed_at         DATETIME(6)  NULL,
  state_changed_by_user_id BINARY(16)   NULL,
  updated_at               DATETIME(6)  NOT NULL,
  version                  BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (branch_id, option_item_id),
  KEY ix_cat_branch_option_item_listing__branch_state (branch_id, availability_state),
  KEY ix_cat_branch_option_item_listing__sold_out_until (sold_out_until),
  CONSTRAINT fk_cat_branch_option_item_listing__option_item_id FOREIGN KEY (option_item_id) REFERENCES cat_option_item (id),
  CONSTRAINT ck_cat_branch_option_item_listing__price CHECK (extra_price IS NULL OR extra_price >= 0)
);
