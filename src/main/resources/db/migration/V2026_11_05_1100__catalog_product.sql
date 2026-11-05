-- Catalog: Product and its Variants (Catalog §5.2, Decision B, Z). DDL from ERD 05-catalog.
CREATE TABLE cat_product (
  id                  BINARY(16)   NOT NULL,
  product_code        VARCHAR(64)  NOT NULL,
  slug                VARCHAR(160) NOT NULL,
  name                VARCHAR(160) NOT NULL,
  short_description   VARCHAR(500) NULL,
  description         TEXT         NULL,
  product_type        VARCHAR(32)  NOT NULL DEFAULT 'STANDARD', -- STANDARD | COMBO (roadmap, Decision B)
  status              VARCHAR(32)  NOT NULL,          -- DRAFT | ACTIVE | INACTIVE | ARCHIVED
  primary_category_id BINARY(16)   NULL,              -- bắt buộc khi ACTIVE (application)
  sort_order          INT          NOT NULL DEFAULT 0,
  archived_at         DATETIME(6)  NULL,
  created_at          DATETIME(6)  NOT NULL,
  updated_at          DATETIME(6)  NOT NULL,
  version             BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_product__product_code (product_code),
  UNIQUE KEY uk_cat_product__slug (slug),
  KEY ix_cat_product__category_status (primary_category_id, status),
  CONSTRAINT fk_cat_product__primary_category_id FOREIGN KEY (primary_category_id) REFERENCES cat_category (id)
);

-- Variant sở hữu full base price; không có Product-level price (§5.2)
CREATE TABLE cat_product_variant (
  id          BINARY(16)   NOT NULL,
  product_id  BINARY(16)   NOT NULL,
  sku         VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,                  -- "Standard" cho simple product
  base_price  BIGINT       NOT NULL,
  is_default  TINYINT(1)   NOT NULL DEFAULT 0,
  status      VARCHAR(32)  NOT NULL,                  -- ACTIVE | INACTIVE | ARCHIVED
  sort_order  INT          NOT NULL DEFAULT 0,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  default_marker BINARY(16) AS (CASE WHEN is_default = 1 AND status <> 'ARCHIVED' THEN product_id END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_product_variant__sku (sku),
  UNIQUE KEY uk_cat_product_variant__default_marker (default_marker),
  KEY ix_cat_product_variant__product_id (product_id),
  CONSTRAINT fk_cat_product_variant__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id),
  CONSTRAINT ck_cat_product_variant__price CHECK (base_price >= 0)
);
