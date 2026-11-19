-- Catalog: curated Collections and Tags (Catalog §5.3, Decision AL). DDL from ERD 05-catalog.
CREATE TABLE cat_collection (
  id          BINARY(16)   NOT NULL,
  slug        VARCHAR(128) NOT NULL,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(500) NULL,
  status      VARCHAR(32)  NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  version     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_collection__slug (slug)
);

CREATE TABLE cat_collection_product (
  collection_id BINARY(16) NOT NULL,
  product_id    BINARY(16) NOT NULL,
  sort_order    INT        NOT NULL DEFAULT 0,
  PRIMARY KEY (collection_id, product_id),
  KEY ix_cat_collection_product__product_id (product_id),
  CONSTRAINT fk_cat_collection_product__collection_id FOREIGN KEY (collection_id) REFERENCES cat_collection (id),
  CONSTRAINT fk_cat_collection_product__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id)
);

CREATE TABLE cat_tag (
  id         BINARY(16)  NOT NULL,
  slug       VARCHAR(64) NOT NULL,
  name       VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_tag__slug (slug)
);

CREATE TABLE cat_product_tag (
  product_id BINARY(16) NOT NULL,
  tag_id     BINARY(16) NOT NULL,
  PRIMARY KEY (product_id, tag_id),
  KEY ix_cat_product_tag__tag_id (tag_id),
  CONSTRAINT fk_cat_product_tag__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id),
  CONSTRAINT fk_cat_product_tag__tag_id FOREIGN KEY (tag_id) REFERENCES cat_tag (id)
);
