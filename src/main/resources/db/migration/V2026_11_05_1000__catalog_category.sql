-- Catalog: Category tree, at most 2 levels (Catalog §5.3, Decision Y). DDL from ERD 05-catalog.
CREATE TABLE cat_category (
  id              BINARY(16)   NOT NULL,
  parent_id       BINARY(16)   NULL,                  -- tối đa 2 cấp (application)
  slug            VARCHAR(128) NOT NULL,
  name            VARCHAR(128) NOT NULL,
  description     VARCHAR(500) NULL,
  image_object_id BINARY(16)   NULL,                  -- → sto_stored_object
  sort_order      INT          NOT NULL DEFAULT 0,
  status          VARCHAR(32)  NOT NULL,              -- ACTIVE | INACTIVE | ARCHIVED
  created_at      DATETIME(6)  NOT NULL,
  updated_at      DATETIME(6)  NOT NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_category__slug (slug),
  CONSTRAINT fk_cat_category__parent_id FOREIGN KEY (parent_id) REFERENCES cat_category (id)
);
