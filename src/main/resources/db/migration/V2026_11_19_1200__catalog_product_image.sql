-- Catalog: images of a Product, one primary (Catalog §5.2, Storage §18.3, Decision AG). DDL from ERD 05-catalog.
CREATE TABLE cat_product_image (
  id               BINARY(16)   NOT NULL,
  product_id       BINARY(16)   NOT NULL,
  stored_object_id BINARY(16)   NOT NULL,             -- → sto_stored_object
  alt_text         VARCHAR(255) NULL,
  sort_order       INT          NOT NULL DEFAULT 0,
  is_primary       TINYINT(1)   NOT NULL DEFAULT 0,
  created_at       DATETIME(6)  NOT NULL,
  primary_marker   BINARY(16) AS (CASE WHEN is_primary = 1 THEN product_id END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_product_image__primary_marker (primary_marker),
  KEY ix_cat_product_image__product_id (product_id),
  CONSTRAINT fk_cat_product_image__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id)
);
