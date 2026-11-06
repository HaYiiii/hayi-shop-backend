-- Catalog: brand-level option library (Catalog §5.4, Decision AB). DDL from ERD 05-catalog.
CREATE TABLE cat_option_group (
  id             BINARY(16)   NOT NULL,
  code           VARCHAR(64)  NOT NULL,
  name           VARCHAR(128) NOT NULL,               -- Size, Đường, Đá, Topping…
  selection_type VARCHAR(32)  NOT NULL,               -- SINGLE | MULTIPLE
  min_select     INT          NOT NULL DEFAULT 0,
  max_select     INT          NOT NULL DEFAULT 1,
  status         VARCHAR(32)  NOT NULL,               -- ACTIVE | INACTIVE | ARCHIVED
  created_at     DATETIME(6)  NOT NULL,
  updated_at     DATETIME(6)  NOT NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_option_group__code (code),
  CONSTRAINT ck_cat_option_group__minmax CHECK (min_select >= 0 AND max_select >= min_select AND max_select >= 1)
);

CREATE TABLE cat_option_item (
  id                  BINARY(16)   NOT NULL,
  option_group_id     BINARY(16)   NOT NULL,
  code                VARCHAR(64)  NOT NULL,
  name                VARCHAR(128) NOT NULL,
  default_extra_price BIGINT       NOT NULL DEFAULT 0, -- library default
  is_default_selected TINYINT(1)   NOT NULL DEFAULT 0,
  status              VARCHAR(32)  NOT NULL,          -- ACTIVE | INACTIVE | ARCHIVED
  sort_order          INT          NOT NULL DEFAULT 0,
  created_at          DATETIME(6)  NOT NULL,
  updated_at          DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_option_item__group_code (option_group_id, code),
  CONSTRAINT fk_cat_option_item__option_group_id FOREIGN KEY (option_group_id) REFERENCES cat_option_group (id),
  CONSTRAINT ck_cat_option_item__price CHECK (default_extra_price >= 0)
);
