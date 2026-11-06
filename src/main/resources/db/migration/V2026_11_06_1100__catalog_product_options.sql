-- Catalog: option configuration of a Product (Catalog §5.4, Decision W4, AC). DDL from ERD 05-catalog.
CREATE TABLE cat_product_option_group (
  id                  BINARY(16) NOT NULL,
  product_id          BINARY(16) NOT NULL,
  option_group_id     BINARY(16) NOT NULL,
  min_select_override INT        NULL,
  max_select_override INT        NULL,
  sort_order          INT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_product_option_group__product_group (product_id, option_group_id),
  CONSTRAINT fk_cat_product_option_group__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id),
  CONSTRAINT fk_cat_product_option_group__option_group_id FOREIGN KEY (option_group_id) REFERENCES cat_option_group (id)
);

-- layer 2: Product override (giá / ẩn item cho món này)
CREATE TABLE cat_product_option_item_override (
  product_id           BINARY(16) NOT NULL,
  option_item_id       BINARY(16) NOT NULL,
  extra_price_override BIGINT     NULL,
  is_hidden            TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (product_id, option_item_id),
  CONSTRAINT fk_cat_product_option_item_override__product_id FOREIGN KEY (product_id) REFERENCES cat_product (id),
  CONSTRAINT fk_cat_product_option_item_override__option_item_id FOREIGN KEY (option_item_id) REFERENCES cat_option_item (id),
  CONSTRAINT ck_cat_product_option_item_override__price CHECK (extra_price_override IS NULL OR extra_price_override >= 0)
);
